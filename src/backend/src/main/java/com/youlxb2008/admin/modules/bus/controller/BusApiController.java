package com.youlxb2008.admin.modules.bus.controller;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.modules.bus.dto.BusMessageCreateReq;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域消息总线生产 API（M1，/api/agent/bus）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope("bus:write")}
 * （ScopeAspect 校验，scope 不足 → 403 + 审计 denied）。
 * <p>
 * 安全红线（方案 §9）：from 强制从 X-API-Key 推导（DTO 无 fromAgentId 字段，body 伪造被
 * Jackson 忽略）；type 白名单 {notify, alert}（request → 422）；分钟/日双频控（超限 429）；
 * sessionKey 强制 bus-* 命名空间；model 须在目标路由白名单内。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/bus")
@Tag(name = "agent 域消息总线", description = "X-API-Key + scope:bus:write，agent 间告知类消息（M1）")
public class BusApiController {

    private final BusMessageService messageService;
    private final AuditService auditService;

    public BusApiController(BusMessageService messageService, AuditService auditService) {
        this.messageService = messageService;
        this.auditService = auditService;
    }

    /**
     * POST /api/agent/bus/messages — 发消息 → 201 { messageId, status[, warning] }
     * （busfix-1007：notify/request 且 payload.content 空白时附 warning，照常入队）
     */
    @Operation(summary = "发消息（from 由 X-API-Key 推导；type 白名单；双频控；sessionKey bus-*；model 白名单）→ 201")
    @RequireScope(value = "bus:write", action = "bus.send")
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ResultVO send(@RequestBody @Validated BusMessageCreateReq req, HttpServletRequest request) {
        KeyIdentity identity = AgentContext.get();
        Long fromAgentId = identity.getAgentId();
        BusMessageService.BusSubmitResult submitted = messageService.sendFromKey(fromAgentId, req);
        audit(identity, "bus.send", submitted.messageId() + "|to=" + req.getToAgentId() + "|type=" + req.getType(),
                "success", request);
        java.util.Map<String, Object> vo = new java.util.HashMap<>();
        vo.put("messageId", submitted.messageId());
        vo.put("status", BusMessageService.STATUS_PENDING);
        if (submitted.warning() != null) {
            vo.put("warning", submitted.warning());
        }
        return ResultVO.getSuccessVO("已入队").setData(vo);
    }

    private void audit(KeyIdentity identity, String action, String resource, String result,
            HttpServletRequest request) {
        String ip = request != null ? request.getRemoteAddr() : "";
        String forwarded = request != null ? request.getHeader("X-Forwarded-For") : null;
        if (forwarded != null && !forwarded.isBlank()) {
            ip = forwarded.split(",")[0].trim();
        }
        String summary = request != null
                ? "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}"
                : null;
        auditService.append(identity.getAgentName(), action, resource, summary, result, ip);
    }
}
