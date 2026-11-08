package com.youlxb2008.admin.modules.objective.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.modules.objective.dto.ObjectiveCreateReq;
import com.youlxb2008.admin.modules.objective.dto.ObjectivePatchReq;
import com.youlxb2008.admin.modules.objective.entity.Objective;
import com.youlxb2008.admin.modules.objective.service.ObjectiveService;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveDetailVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveVO;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域目标 API Controller（M4.1，/api/agent/objectives）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope("objective:write")}
 * （ScopeAspect 校验；scope 不足 → 403 + 审计 denied）。
 * 目标为每 agent 私域 todolist：PATCH/DELETE/详情均做 agent_id 归属校验（非本人统一 404 防枚举）。
 * 审计 action：objective.create/update/delete（t_audit_log）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/objectives")
@Tag(name = "agent 域目标 API", description = "X-API-Key 认证 + scope:objective:write，每 agent 私域 todolist")
public class ObjectiveApiController {

	@Autowired
	private ObjectiveService objectiveService;

	@Autowired
	private AuditService auditService;

	/**
	 * GET /api/agent/objectives — 自己（当前 agent）的目标列表（含 progress/status/period/targetDate）
	 */
	@Operation(summary = "自己的目标列表（私域 todolist）")
	@RequireScope(value = "objective:write", action = "objective.read")
	@GetMapping
	public ResultVO list(HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		List<ObjectiveVO> list = objectiveService.listOwn(identity.getAgentId());
		audit(identity, "objective.read", "/api/agent/objectives", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * POST /api/agent/objectives — 新建目标（title 必填；写详情 md + 索引 + 作者关联）
	 */
	@Operation(summary = "新建目标（title 必填；写 md 模板 + 索引 + 作者关联行）")
	@RequireScope(value = "objective:write", action = "objective.create")
	@PostMapping
	public ResultVO create(@RequestBody @Validated ObjectiveCreateReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Objective obj = objectiveService.create(identity.getAgentId(), identity.getAgentName(), req);
		audit(identity, "objective.create", obj.getOid() + "|title=" + obj.getTitle(), "success", request);
		return ResultVO.getSuccessVO("创建成功")
				.setData(java.util.Map.of("oid", obj.getOid(), "title", obj.getTitle(), "progress",
						obj.getProgress(), "updateTime", obj.getUpdateTime()));
	}

	/**
	 * GET /api/agent/objectives/{oid} — 自己目标详情（含描述 md 全文 + 子项解析结果）
	 * <p>
	 * 私域归属校验：非本人 / 不存在 → 404（防枚举）。
	 */
	@Operation(summary = "自己目标详情（md 全文 + checkbox 子项解析）")
	@RequireScope(value = "objective:write", action = "objective.read")
	@GetMapping("/{oid}")
	public ResultVO detail(@PathVariable String oid, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		objectiveService.requireOwned(identity.getAgentId(), oid);
		ObjectiveDetailVO vo = objectiveService.getDetail(oid);
		audit(identity, "objective.read", oid, "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PATCH /api/agent/objectives/{oid} — 部分改（title/period/targetDate/status/描述 md 覆写）→ 重算 progress
	 * <p>
	 * 私域归属校验（非本人 → 404）；全空 body → 400。
	 */
	@Operation(summary = "部分改目标（改 descMd 覆写文件描述段并重算 progress）")
	@RequireScope(value = "objective:write", action = "objective.update")
	@PatchMapping("/{oid}")
	public ResultVO patch(@PathVariable String oid, @RequestBody ObjectivePatchReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			Objective obj = objectiveService.patchMeta(identity.getAgentId(), oid, req);
			audit(identity, "objective.update", oid + "|title=" + obj.getTitle(), "success", request);
			return ResultVO.getSuccessVO("更新成功")
					.setData(java.util.Map.of("oid", obj.getOid(), "title", obj.getTitle(), "status",
							obj.getStatus(), "progress", obj.getProgress(), "updateTime",
							obj.getUpdateTime()));
		} catch (Exception e) {
			audit(identity, "objective.update", oid, "error", request);
			throw e;
		}
	}

	/**
	 * DELETE /api/agent/objectives/{oid} — 软删（is_deleted=1，md 保留）
	 * <p>
	 * 私域归属校验（非本人 → 404）。
	 */
	@Operation(summary = "软删目标（is_deleted=1，md 文件保留）")
	@RequireScope(value = "objective:write", action = "objective.delete")
	@DeleteMapping("/{oid}")
	public ResultVO delete(@PathVariable String oid, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			objectiveService.delete(identity.getAgentId(), oid);
			audit(identity, "objective.delete", oid, "success", request);
			return ResultVO.getSuccessVO("删除成功");
		} catch (Exception e) {
			audit(identity, "objective.delete", oid, "error", request);
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
