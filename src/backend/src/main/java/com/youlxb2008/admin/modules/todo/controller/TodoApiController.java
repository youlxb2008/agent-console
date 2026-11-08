package com.youlxb2008.admin.modules.todo.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.modules.todo.dto.TodoCreateReq;
import com.youlxb2008.admin.modules.todo.dto.TodoPatchReq;
import com.youlxb2008.admin.modules.todo.service.TodoService;
import com.youlxb2008.admin.modules.todo.vo.TodoVO;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域待办 API Controller（M5.2，/api/agent/todos）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope("todo:write")}
 * （ScopeAspect 校验；scope 不足 → 403 + 审计 denied）。
 * 待办为每 agent 私域 todolist：越权（操作非本人 todo）→ 403，不存在/已软删 → 404（任务书契约）。
 * 审计 action：todo.read/create/update/delete（t_audit_log）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/todos")
@Tag(name = "agent 域待办 API", description = "X-API-Key 认证 + scope:todo:write，每 agent 私域 todolist")
public class TodoApiController {

	/** 分页：每页默认条数 / 单页上限 */
	private static final long PAGE_DEFAULT_SIZE = 20L;
	private static final long PAGE_MAX_SIZE = 50L;

	@Autowired
	private TodoService todoService;

	@Autowired
	private AuditService auditService;

	/**
	 * GET /api/agent/todos — 自己的待办分页（M5.3 排序：有截止日期在前按日期升序，无日期按优先级；status/priority 筛选）
	 */
	@Operation(summary = "自己的待办分页（有截止日期在前按日期升序，无日期按 priority 降序；status/priority 筛选）")
	@RequireScope(value = "todo:write", action = "todo.read")
	@GetMapping
	public ResultVO list(@RequestParam(required = false) Integer status,
			@RequestParam(required = false) Integer priority,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long pageSize,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		long size = Math.min(Math.max(pageSize, 1), PAGE_MAX_SIZE);
		IPage<TodoVO> result = todoService.pageOwn(identity.getAgentId(), status, priority, page, size);
		audit(identity, "todo.read", "/api/agent/todos", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}

	/**
	 * POST /api/agent/todos — 新建待办（title 必填）→ 201 返回 VO
	 */
	@Operation(summary = "新建待办（title 必填；status/priority 有默认，tags 逗号串）→ 201 返回 VO")
	@RequireScope(value = "todo:write", action = "todo.create")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ResultVO create(@RequestBody @Validated TodoCreateReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		TodoVO vo = todoService.create(identity.getAgentId(), req);
		audit(identity, "todo.create", vo.getTodoId() + "|title=" + vo.getTitle(), "success", request);
		return ResultVO.getSuccessVO("创建成功").setData(vo);
	}

	/**
	 * GET /api/agent/todos/{todoId} — 待办详情（含 content；越权 403/不存在 404）
	 */
	@Operation(summary = "待办详情（含 content；越权 403 / 不存在或已软删 404）")
	@RequireScope(value = "todo:write", action = "todo.read")
	@GetMapping("/{todoId}")
	public ResultVO detail(@PathVariable String todoId, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		TodoVO vo = todoService.getDetailOwned(identity.getAgentId(), todoId);
		audit(identity, "todo.read", todoId, "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PATCH /api/agent/todos/{todoId} — 部分改（title/content/status/priority/tags/dueDate 任意子集）
	 */
	@Operation(summary = "部分改待办（任意子集，dueDate null=不修改；全空 400；越权 403 / 不存在 404）")
	@RequireScope(value = "todo:write", action = "todo.update")
	@PatchMapping("/{todoId}")
	public ResultVO patch(@PathVariable String todoId, @RequestBody TodoPatchReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			TodoVO vo = todoService.patchMeta(identity.getAgentId(), todoId, req);
			audit(identity, "todo.update", todoId + "|title=" + vo.getTitle(), "success", request);
			return ResultVO.getSuccessVO("更新成功").setData(vo);
		} catch (Exception e) {
			audit(identity, "todo.update", todoId, "error", request);
			throw e;
		}
	}

	/**
	 * DELETE /api/agent/todos/{todoId} — 软删（is_deleted=1；越权 403/不存在 404）
	 */
	@Operation(summary = "软删待办（is_deleted=1；越权 403 / 不存在或已软删 404）")
	@RequireScope(value = "todo:write", action = "todo.delete")
	@DeleteMapping("/{todoId}")
	public ResultVO delete(@PathVariable String todoId, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			todoService.delete(identity.getAgentId(), todoId);
			audit(identity, "todo.delete", todoId, "success", request);
			return ResultVO.getSuccessVO("删除成功");
		} catch (Exception e) {
			audit(identity, "todo.delete", todoId, "error", request);
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
