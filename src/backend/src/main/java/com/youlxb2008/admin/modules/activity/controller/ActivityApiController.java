package com.youlxb2008.admin.modules.activity.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.activity.dto.ActivityBatchReq;
import com.youlxb2008.admin.modules.activity.service.ActivityService;
import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域活动流上报 API Controller（M6.1，/api/agent/activity）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope("activity:write")}
 * （ScopeAspect 校验；scope 不足 → 403 + 审计 denied）。
 * <p>
 * <b>成功不写 t_audit_log（与 todo 等 controller 的逐次 success 审计惯例刻意不同）</b>：
 * 插件 2s 一批 ≈ 30 req/min，逐批写审计会把审计表刷爆；scope denied 由 ScopeAspect 自动兜底审计。
 * <p>
 * agent_id 取自 key 身份（AgentContext），不信任请求体。
 * 入库失败整体 500，插件侧 fail-open 丢弃不重试（无幂等要求，允许丢批）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/activity")
@Tag(name = "agent 域活动流 API", description = "X-API-Key 认证 + scope:activity:write，批量上报 agent 实时活动事件")
public class ActivityApiController {

	@Autowired
	private ActivityService activityService;

	/**
	 * POST /api/agent/activity — 批量上报活动事件（1~50 条）→ {"accepted":N}
	 */
	@Operation(summary = "批量上报活动事件（items 非空且 ≤50；eventType 白名单四值；summary 服务端截断 512）")
	@RequireScope(value = "activity:write", action = "activity.report")
	@PostMapping
	@ResponseStatus(HttpStatus.OK)
	public ResultVO report(@RequestBody ActivityBatchReq req) {
		KeyIdentity identity = AgentContext.get();
		// agent_id 取自 key 身份，不信任请求体
		int accepted = activityService.report(identity.getAgentId(), req);
		// 成功不写审计：插件 2s 一批 ≈ 30 req/min，逐批写审计会刷爆审计表（denied 由 ScopeAspect 兜底）
		return ResultVO.getSuccessVO("上报成功").setData(Map.of("accepted", accepted));
	}
}
