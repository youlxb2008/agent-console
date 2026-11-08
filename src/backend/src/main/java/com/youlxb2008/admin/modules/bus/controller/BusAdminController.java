package com.youlxb2008.admin.modules.bus.controller;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.youlxb2008.admin.modules.bus.dto.BusDebugSendReq;
import com.youlxb2008.admin.modules.bus.dto.BusManualSendReq;
import com.youlxb2008.admin.modules.bus.dto.BusRouteSaveReq;
import com.youlxb2008.admin.modules.bus.dispatch.BusDispatchService;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.service.BusChannelRouteService;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.exceptions.UnprocessableEntityException;
import com.youlxb2008.admin.modules.bus.vo.BusMessageVO;
import com.youlxb2008.admin.modules.bus.vo.BusStatsVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.context.UserContext;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.BPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息总线管理端（M1，JWT 域，权限 bus:message:*，方案 §4.2）
 * <p>
 * 五端点：分页列表 / 详情 / 手动重投 / 手动发消息 / 统计。
 * 手动发消息可任选 from 模拟生产者（演练与 e2e 工具），走与生产 API 完全同一校验链。
 */
@Slf4j
@RestController
@RequestMapping("/system/bus")
@RequiredArgsConstructor
@Tag(name = "消息总线", description = "agent 消息总线管理（列表/详情/重投/手动发消息/统计）")
public class BusAdminController {

    /** 统计默认窗口（天） */
    private static final int STATS_DEFAULT_DAYS = 7;
    private static final int STATS_MAX_DAYS = 90;

    private final BusMessageService messageService;
    private final BusChannelRouteService channelRouteService;
    private final AuditService auditService;

    @Operation(summary = "分页列表（筛选：fromAgent/toAgent/status/type）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/messages")
    public ResultVO list(@RequestParam(required = false) Long fromAgent,
            @RequestParam(required = false) Long toAgent,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        BPage pageData = messageService.page(fromAgent, toAgent, status, type, page, size);
        return ResultVO.getSuccessVO("查询成功").setData(pageData);
    }

    @Operation(summary = "详情（完整 payload + session/model + last_error）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/messages/{id}")
    public ResultVO detail(@PathVariable Long id) {
        BusMessageVO vo = messageService.detail(id);
        return ResultVO.getSuccessVO("查询成功").setData(vo);
    }

    @Operation(summary = "手动重投（status∈{0,3} 可用，重入 XADD；前端二次确认）")
    @RequiresPermissions("bus:message:retry")
    @PutMapping("/messages/{id}/retry")
    public ResultVO retry(@PathVariable Long id, HttpServletRequest request) {
        messageService.retry(id);
        audit("bus.message.retry", "id=" + id, "success", request);
        return ResultVO.getSuccessVO("已重入投递队列");
    }

    @Operation(summary = "手动发消息（可任选 from 模拟生产者；走同一校验链）")
    @RequiresPermissions("bus:message:send")
    @PostMapping("/messages")
	@ResponseStatus(HttpStatus.CREATED)
    public ResultVO send(@RequestBody @Validated BusManualSendReq req, HttpServletRequest request) {
        BusMessageService.BusSubmitResult submitted = messageService.sendManual(req);
        audit("bus.message.send", submitted.messageId() + "|from=" + req.getFromAgentId() + "|to=" + req.getToAgentId(),
                "success", request);
        java.util.Map<String, Object> vo = new java.util.HashMap<>();
        vo.put("messageId", submitted.messageId());
        vo.put("status", BusMessageService.STATUS_PENDING);
        if (submitted.warning() != null) {
            vo.put("warning", submitted.warning());
        }
        return ResultVO.getSuccessVO("已入队").setData(vo);
    }

    @Operation(summary = "统计（今日进线/待投/失败/平均投递耗时）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/stats")
    public ResultVO stats(@RequestParam(defaultValue = "7") int days) {
        int window = Math.min(Math.max(days, 1), STATS_MAX_DAYS);
        BusStatsVO vo = messageService.stats(window);
        return ResultVO.getSuccessVO("查询成功").setData(vo);
    }

    @Operation(summary = "投递路由矩阵（全部 agent 两两方向含自投，无配置行默认 ws；bus-route-channel）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/routes")
    public ResultVO routes() {
        return ResultVO.getSuccessVO("查询成功").setData(channelRouteService.matrix());
    }

    @Operation(summary = "保存投递路由（单方向 upsert，即时生效；channel 仅 ws/api，from != to）")
    @RequiresPermissions("bus:message:send")
    @PutMapping("/routes")
    public ResultVO saveRoute(@RequestBody BusRouteSaveReq req, HttpServletRequest request) {
        if (req == null || req.getFromAgentId() == null || req.getToAgentId() == null) {
            throw new UnprocessableEntityException("fromAgentId/toAgentId 必填");
        }
        channelRouteService.save(req.getFromAgentId(), req.getToAgentId(), req.getChannel());
        audit("bus.route.save", req.getFromAgentId() + "->" + req.getToAgentId() + "|" + req.getChannel(),
                "success", request);
        return ResultVO.getSuccessVO("已保存，下一条消息按新通道投递");
    }

    @Operation(summary = "批量保存投递路由（整表提交，逐行 upsert；任一行非法整体不落库）")
    @RequiresPermissions("bus:message:send")
    @PutMapping("/routes/batch")
    public ResultVO saveRoutes(@RequestBody List<BusRouteSaveReq> items, HttpServletRequest request) {
        int count = channelRouteService.saveBatch(items);
        audit("bus.route.saveBatch", "count=" + count, "success", request);
        return ResultVO.getSuccessVO("已保存 " + count + " 条方向路由");
    }

    @Operation(summary = "恢复默认：删除某发件方全部路由行（无配置行 = 默认 ws；bus-ui-v2）")
    @RequiresPermissions("bus:message:send")
    @DeleteMapping("/routes/{fromAgentId}")
    public ResultVO clearRoutes(@PathVariable Long fromAgentId, HttpServletRequest request) {
        int deleted = channelRouteService.clearByFrom(fromAgentId);
        audit("bus.route.clear", "from=" + fromAgentId + "|rows=" + deleted, "success", request);
        return ResultVO.getSuccessVO("已恢复默认（删除 " + deleted + " 行，回落默认 ws）");
    }

    @Operation(summary = "投递通道可用性（解析 BUS_TARGET_*/BUS_API_TARGET_*；两项均 false=无实例；bus-ui-v2）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/delivery-targets")
    public ResultVO deliveryTargets() {
        return ResultVO.getSuccessVO("查询成功").setData(messageService.deliveryTargets());
    }

    @Operation(summary = "方向近 24h 消息计数（bus-ui-v2）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/route-stats")
    public ResultVO routeStats() {
        return ResultVO.getSuccessVO("查询成功").setData(messageService.routeStats24h());
    }

    // ==================== 总线调试（v4.37 bus-sessionkey-fix，仅管理端） ====================

    /** 调试发送固定来源：agentId=1（默认管理员 agent；在 BUS_TRUSTED_AGENTS 默认白名单内） */
    private static final long DEBUG_FROM_AGENT_ID = 1L;
    /** 调试消息固定 topic */
    private static final String DEBUG_TOPIC = "debug";

    @Operation(summary = "总线调试：查方向路由（from 固定为管理员 agent；无配置行 = 默认 ws）")
    @RequiresPermissions("bus:message:list")
    @GetMapping("/debug/route")
    public ResultVO debugRoute(@RequestParam(required = false) Long toAgentId) {
        if (toAgentId == null) {
            throw new UnprocessableEntityException("toAgentId 必填");
        }
        java.util.Map<String, Object> vo = new java.util.HashMap<>();
        vo.put("fromAgentId", DEBUG_FROM_AGENT_ID);
        vo.put("toAgentId", toAgentId);
        vo.put("channel", channelRouteService.resolveChannel(DEBUG_FROM_AGENT_ID, toAgentId));
        vo.put("hasExplicitRoute", channelRouteService.hasExplicitRoute(DEBUG_FROM_AGENT_ID, toAgentId));
        return ResultVO.getSuccessVO("查询成功").setData(vo);
    }

    /**
     * 总线调试发送：临时切路由 → 转调总线发送（sessionKey 原样，落点靠 dispatcher 剥前缀）
     * → finally 还原原路由（有行还原 channel / 无行删行回落默认 ws）。
     * 调试工具，竞态窗口毫秒级可接受；发送失败（422 校验等）同样还原后抛出。
     */
    @Operation(summary = "总线调试发送：临时切路由→发送→还原；返回 messageId/预期落点预览")
    @RequiresPermissions("bus:message:send")
    @PostMapping("/debug/send")
    public ResultVO debugSend(@RequestBody @Validated BusDebugSendReq req, HttpServletRequest request) {
        if (!BusRoute.CHANNEL_WS.equals(req.getChannel()) && !BusRoute.CHANNEL_API.equals(req.getChannel())) {
            throw new UnprocessableEntityException("channel 仅支持 ws/api: " + req.getChannel());
        }
        long to = req.getToAgentId();
        boolean existed = channelRouteService.hasExplicitRoute(DEBUG_FROM_AGENT_ID, to);
        String original = channelRouteService.resolveChannel(DEBUG_FROM_AGENT_ID, to);
        channelRouteService.save(DEBUG_FROM_AGENT_ID, to, req.getChannel());
        String messageId;
        try {
            BusManualSendReq manual = new BusManualSendReq();
            manual.setFromAgentId(DEBUG_FROM_AGENT_ID);
            manual.setToAgentId(to);
            manual.setType("notify");
            manual.setTopic(DEBUG_TOPIC);
            manual.setTitle(req.getTitle() == null || req.getTitle().isBlank()
                    ? "总线调试" : req.getTitle());
            manual.setPayload(java.util.Map.of("content", req.getContent()));
            manual.setSessionKey(req.getSessionKey());
            // 调试消息无需回音，显式免熔断（bus-fuse-zero-optout）——不设值走全局默认时限，
            // 每条调试消息都会武装 topic=debug 方向保险丝，目标不回复即向管理员告警会话弹 [fuse] 告警
            manual.setExpectReplyMinutes(0);
            messageId = messageService.sendManual(manual).messageId();
        } finally {
            if (existed) {
                channelRouteService.save(DEBUG_FROM_AGENT_ID, to, original);
            } else {
                channelRouteService.deleteRoute(DEBUG_FROM_AGENT_ID, to);
            }
            log.info("[agent-bus] debug 临时切路由已还原: to={} 恢复为{}", to,
                    existed ? original : "（删行回落默认 ws）");
        }
        AgentMessage row = messageService.findByMessageId(messageId);
        String expectedChatId = BusDispatchService.stripChannelPrefix(req.getSessionKey());
        java.util.Map<String, Object> vo = new java.util.HashMap<>();
        vo.put("messageId", messageId);
        vo.put("dbId", row != null ? row.getId() : null);
        vo.put("channelTemp", req.getChannel());
        vo.put("channelBefore", original);
        vo.put("routeRestoredTo", existed ? original : BusRoute.CHANNEL_WS);
        vo.put("expectedChatId", expectedChatId);
        vo.put("expectedSessionKey",
                req.getSessionKey().matches(BusMessageService.SESSION_KEY_PATTERN)
                        ? req.getSessionKey()
                        : "websocket:" + expectedChatId);
        audit("bus.debug.send", messageId + "|to=" + to + "|session=" + req.getSessionKey()
                + "|channel=" + req.getChannel(), "success", request);
        return ResultVO.getSuccessVO("已入队（调试路由已还原）").setData(vo);
    }

    /** 管理端操作审计（principal=当前登录用户名，与 agent 域审计同表） */
    private void audit(String action, String resource, String result, HttpServletRequest request) {
        User user = UserContext.getUser();
        String principal = user != null ? user.getUsername() : "unknown";
        String ip = request != null ? request.getRemoteAddr() : "";
        String forwarded = request != null ? request.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            ip = forwarded.split(",")[0].trim();
        }
        String summary = request != null
                ? "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}"
                : null;
        auditService.append(principal, action, resource, summary, result, ip);
    }
}
