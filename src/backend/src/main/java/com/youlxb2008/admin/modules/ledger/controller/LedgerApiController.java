package com.youlxb2008.admin.modules.ledger.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.modules.ledger.dto.LedgerCreateReq;
import com.youlxb2008.admin.modules.ledger.dto.LedgerPatchReq;
import com.youlxb2008.admin.modules.ledger.entity.Ledger;
import com.youlxb2008.admin.modules.ledger.service.LedgerService;
import com.youlxb2008.admin.modules.ledger.vo.LedgerDetailVO;
import com.youlxb2008.admin.modules.ledger.vo.LedgerVO;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域台账 API Controller（M4.2，/api/agent/ledger）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope("ledger:write")}
 * （ScopeAspect 校验；scope 不足 → 403 + 审计 denied）。
 * 台账为每 agent 私域流水：PATCH/DELETE/详情均做 agent_id 归属校验（非本人统一 404 防枚举）。
 * 审计 action：ledger.read/create/update/delete（t_audit_log）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/ledger")
@Tag(name = "agent 域台账 API", description = "X-API-Key 认证 + scope:ledger:write，每 agent 私域流水")
public class LedgerApiController {

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private AuditService auditService;

	/**
	 * GET /api/agent/ledger — 自己的台账流（倒序；?type=&refTaskId=&refRequirementId= 筛选）
	 */
	@Operation(summary = "自己的台账流（create_time 倒序；type/ref 筛选）")
	@RequireScope(value = "ledger:write", action = "ledger.read")
	@GetMapping
	public ResultVO list(@RequestParam(required = false) String type,
			@RequestParam(required = false) String refTaskId,
			@RequestParam(required = false) String refRequirementId,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		List<LedgerVO> list = ledgerService.listOwn(identity.getAgentId(), type, refTaskId, refRequirementId);
		audit(identity, "ledger.read", "/api/agent/ledger", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * POST /api/agent/ledger — 记一笔（type/title 必填；body 双轨切分：≤500 字 inline、>500 字 md）
	 */
	@Operation(summary = "记一笔（type/title 必填；body 双轨：≤500 字 inline、>500 字写 md）")
	@RequireScope(value = "ledger:write", action = "ledger.create")
	@PostMapping
	public ResultVO create(@RequestBody @Validated LedgerCreateReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Ledger row = ledgerService.create(identity.getAgentId(), identity.getAgentName(), req);
		audit(identity, "ledger.create", row.getLid() + "|type=" + row.getType() + "|title=" + row.getTitle(),
				"success", request);
		return ResultVO.getSuccessVO("记录成功")
				.setData(Map.of("lid", row.getLid(), "type", row.getType(), "title", row.getTitle(),
						"createTime", row.getCreateTime()));
	}

	/**
	 * GET /api/agent/ledger/{lid} — 自己的台账详情（读正文：inline 优先，否则读 md）
	 * <p>
	 * 私域归属校验：非本人 / 不存在 → 404（防枚举）。
	 */
	@Operation(summary = "自己的台账详情（读正文：inline 优先，否则读 md）")
	@RequireScope(value = "ledger:write", action = "ledger.read")
	@GetMapping("/{lid}")
	public ResultVO detail(@PathVariable String lid, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		ledgerService.requireOwned(identity.getAgentId(), lid);
		LedgerDetailVO vo = ledgerService.getDetail(lid);
		audit(identity, "ledger.read", lid, "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PATCH /api/agent/ledger/{lid} — 部分改（type/title/body/ref 四列）→ body 双轨重切分
	 * <p>
	 * 私域归属校验（非本人 → 404）；全空 body → 400。
	 */
	@Operation(summary = "部分改台账（改 body 按双轨重切分：inline↔md 互转，原 md 保留）")
	@RequireScope(value = "ledger:write", action = "ledger.update")
	@PatchMapping("/{lid}")
	public ResultVO patch(@PathVariable String lid, @RequestBody LedgerPatchReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			Ledger row = ledgerService.patchMeta(identity.getAgentId(), lid, req);
			audit(identity, "ledger.update", lid + "|type=" + row.getType() + "|title=" + row.getTitle(),
					"success", request);
			return ResultVO.getSuccessVO("更新成功")
					.setData(Map.of("lid", row.getLid(), "type", row.getType(), "title", row.getTitle()));
		} catch (Exception e) {
			audit(identity, "ledger.update", lid, "error", request);
			throw e;
		}
	}

	/**
	 * DELETE /api/agent/ledger/{lid} — 软删（is_deleted=1，有 body_path 时文件保留不删）
	 * <p>
	 * 私域归属校验（非本人 → 404）。
	 */
	@Operation(summary = "软删台账（is_deleted=1，长正文 md 文件保留）")
	@RequireScope(value = "ledger:write", action = "ledger.delete")
	@DeleteMapping("/{lid}")
	public ResultVO delete(@PathVariable String lid, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			ledgerService.delete(identity.getAgentId(), lid);
			audit(identity, "ledger.delete", lid, "success", request);
			return ResultVO.getSuccessVO("删除成功");
		} catch (Exception e) {
			audit(identity, "ledger.delete", lid, "error", request);
			throw e;
		}
	}

	private void audit(KeyIdentity identity, String action, String resource, String result,
			HttpServletRequest request) {
		auditService.append(identity != null ? identity.getAgentName() : "unknown", action, resource,
				summary(request), result, getClientIp(request));
	}

	private String getClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("X-Real-IP");
		}
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return ip;
	}

	private String summary(HttpServletRequest request) {
		return "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}";
	}
}
