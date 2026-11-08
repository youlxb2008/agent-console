package com.youlxb2008.admin.modules.agent.memory.controller;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryCreateReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemorySupersedeReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryUpdateReq;
import com.youlxb2008.admin.modules.agent.memory.service.MemoryService;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryDetailVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemorySearchHitVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryVO;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.utils.ResultVO;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域记忆 API Controller（M7.1，/api/agent/memories，九端点）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope}
 * （ScopeAspect 校验；scope 不足 → 403 + 审计 denied）。
 * 新增 scope：{@code memory:read}（列表/详情/检索）、{@code memory:write}
 * （写入/更新/确认/取代/恢复/删除）。
 * <p>
 * 权限边界（任务书硬性）：所有读写强制 agent_id = 当前 key 身份的 agentId，
 * 跨 agent 访问一律 404（fail-closed，不泄露存在性）。
 * 审计 action：memory.create/read/search/update/confirm/supersede/restore/delete。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/memories")
@Tag(name = "agent 域记忆 API", description = "X-API-Key 认证 + scope:memory:read/memory:write，agent 私域长期记忆库")
public class MemoryApiController {

	/** 分页：每页默认条数 / 单页上限 */
	private static final long PAGE_DEFAULT_SIZE = 20L;
	private static final long PAGE_MAX_SIZE = 50L;
	/** 检索：默认返回条数 / 上限（任务书 §2.2） */
	private static final int SEARCH_DEFAULT_LIMIT = 10;
	private static final int SEARCH_MAX_LIMIT = 30;
	/** M7.1-M5 追加：since/until 合法格式（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss） */
	private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	@Autowired
	private MemoryService memoryService;

	@Autowired
	private AuditService auditService;

	/**
	 * POST /api/agent/memories — 写入记忆 → 201 返回 VO
	 */
	@Operation(summary = "写入记忆（memType/source 白名单校验；status 默认 1/weight 默认 0）→ 201 返回 VO")
	@RequireScope(value = "memory:write", action = "memory.create")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ResultVO create(@RequestBody @Validated MemoryCreateReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		MemoryVO vo = memoryService.create(identity.getAgentId(), req);
		audit(identity, "memory.create", vo.getId() + "|title=" + vo.getTitle(), "success", request);
		return ResultVO.getSuccessVO("创建成功").setData(vo);
	}

	/**
	 * GET /api/agent/memories — 自己的记忆分页（status/memType/tag/since/until 筛选 + sort 排序）
	 * <p>
	 * M7.1-M5：sort 缺省/updated=update_time DESC,id DESC（现状默认）；weight=weight DESC,id DESC；
	 * 其他值 400。M7.1-M5 追加：since/until 筛 create_time（含边界当天），非法格式 400。
	 */
	@Operation(summary = "自己的记忆分页（status/memType/tag/since/until 筛选；sort=updated|weight，缺省 update_time 倒序）")
	@RequireScope(value = "memory:read", action = "memory.read")
	@GetMapping
	public ResultVO list(@RequestParam(required = false) Integer status,
			@RequestParam(required = false) String memType,
			@RequestParam(required = false) String tag,
			@RequestParam(required = false) String sort,
			@RequestParam(required = false) String since,
			@RequestParam(required = false) String until,
			@RequestParam(required = false, defaultValue = "1") long page,
			@RequestParam(required = false, defaultValue = "" + PAGE_DEFAULT_SIZE) long pageSize,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		long size = Math.min(Math.max(pageSize, 1), PAGE_MAX_SIZE);
		// M7.1-M5：sort 白名单校验（Service 收到即信任，不重复校验）
		if (sort != null && !MemoryService.SORT_UPDATED.equals(sort) && !MemoryService.SORT_WEIGHT.equals(sort)) {
			throw new BadRequestException("sort 仅支持 updated（按更新时间倒序，默认）或 weight（按重要程度倒序）");
		}
		// M7.1-M5 追加：since/until 解析（筛 create_time；yyyy-MM-dd 展开为当天 00:00:00 / 23:59:59）
		Date sinceTime = parseTimeParam(since, "since", false);
		Date untilTime = parseTimeParam(until, "until", true);
		IPage<MemoryVO> result = memoryService.pageOwn(identity.getAgentId(), status, memType, tag,
				sort, sinceTime, untilTime, page, size);
		audit(identity, "memory.read", "/api/agent/memories", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}

	/**
	 * GET /api/agent/memories/search — Lucene 检索（命中即刷新 access_count/last_accessed_at）
	 */
	@Operation(summary = "Lucene 检索（q 必填；limit 默认 10 上限 30；命中即 access_count+1/last_accessed_at=now；归档/被取代参与检索带 status 标记）")
	@RequireScope(value = "memory:read", action = "memory.search")
	@GetMapping("/search")
	public ResultVO search(@RequestParam String q,
			@RequestParam(required = false, defaultValue = "" + SEARCH_DEFAULT_LIMIT) int limit,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		var hits = memoryService.search(identity.getAgentId(), q, limit);
		audit(identity, "memory.search", "q=" + q + "|hits=" + hits.size(), "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(hits);
	}

	/**
	 * GET /api/agent/memories/{id} — 详情（含 superseded 链回溯）
	 */
	@Operation(summary = "记忆详情（含 contentMd 全文与 superseded 链回溯：沿 superseded_by 找链尾最新版）")
	@RequireScope(value = "memory:read", action = "memory.read")
	@GetMapping("/{id}")
	public ResultVO detail(@PathVariable Long id, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		MemoryDetailVO vo = memoryService.detail(identity.getAgentId(), id);
		audit(identity, "memory.read", String.valueOf(id), "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PUT /api/agent/memories/{id} — 更新（title/contentMd/memType/tags/refUrl 任意子集）
	 */
	@Operation(summary = "更新记忆（任意子集，null=不修改；全空 400；memType 传入时白名单校验）")
	@RequireScope(value = "memory:write", action = "memory.update")
	@PutMapping("/{id}")
	public ResultVO update(@PathVariable Long id, @RequestBody @Validated MemoryUpdateReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			MemoryVO vo = memoryService.update(identity.getAgentId(), id, req);
			audit(identity, "memory.update", id + "|title=" + vo.getTitle(), "success", request);
			return ResultVO.getSuccessVO("更新成功").setData(vo);
		} catch (Exception e) {
			audit(identity, "memory.update", String.valueOf(id), "error", request);
			throw e;
		}
	}

	/**
	 * POST /api/agent/memories/{id}/confirm — 确认（confirmed_at=now；weight 由 cron 算）
	 */
	@Operation(summary = "确认记忆（confirmed_at=now；weight 由 memoryDecayJob 全量重算，此处只刷新时间）")
	@RequireScope(value = "memory:write", action = "memory.confirm")
	@PostMapping("/{id}/confirm")
	public ResultVO confirm(@PathVariable Long id, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			memoryService.confirm(identity.getAgentId(), id);
			audit(identity, "memory.confirm", String.valueOf(id), "success", request);
			return ResultVO.getSuccessVO("确认成功");
		} catch (Exception e) {
			audit(identity, "memory.confirm", String.valueOf(id), "error", request);
			throw e;
		}
	}

	/**
	 * POST /api/agent/memories/{id}/supersede — 取代（旧条 status=3 + superseded_by=新 id）
	 */
	@Operation(summary = "取代记忆（body={newMemoryId} 关联已有；或 body 直接带新记忆字段=创建新条并关联；旧条 status=3）")
	@RequireScope(value = "memory:write", action = "memory.supersede")
	@PostMapping("/{id}/supersede")
	public ResultVO supersede(@PathVariable Long id, @RequestBody MemorySupersedeReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			MemoryVO vo = memoryService.supersede(identity.getAgentId(), id, req);
			audit(identity, "memory.supersede", id + "->" + vo.getId(), "success", request);
			return ResultVO.getSuccessVO("取代成功").setData(vo);
		} catch (Exception e) {
			audit(identity, "memory.supersede", String.valueOf(id), "error", request);
			throw e;
		}
	}

	/**
	 * POST /api/agent/memories/{id}/restore — 恢复（仅 archived 2→1）
	 */
	@Operation(summary = "恢复记忆（仅归档状态 status=2 可恢复为 1；被取代 3 不可直接恢复）")
	@RequireScope(value = "memory:write", action = "memory.restore")
	@PostMapping("/{id}/restore")
	public ResultVO restore(@PathVariable Long id, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			MemoryVO vo = memoryService.restore(identity.getAgentId(), id);
			audit(identity, "memory.restore", String.valueOf(id), "success", request);
			return ResultVO.getSuccessVO("恢复成功").setData(vo);
		} catch (Exception e) {
			audit(identity, "memory.restore", String.valueOf(id), "error", request);
			throw e;
		}
	}

	/**
	 * DELETE /api/agent/memories/{id} — 逻辑删除（is_deleted=1 + 移除索引文档）
	 */
	@Operation(summary = "删除记忆（逻辑删除 is_deleted=1；索引文档同步移除，不再参与检索）")
	@RequireScope(value = "memory:write", action = "memory.delete")
	@DeleteMapping("/{id}")
	public ResultVO delete(@PathVariable Long id, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			memoryService.delete(identity.getAgentId(), id);
			audit(identity, "memory.delete", String.valueOf(id), "success", request);
			return ResultVO.getSuccessVO("删除成功");
		} catch (Exception e) {
			audit(identity, "memory.delete", String.valueOf(id), "error", request);
			throw e;
		}
	}

	/**
	 * M7.1-M5 追加：since/until 参数解析。
	 * <p>
	 * 合法格式：{@code yyyy-MM-dd}（since 展开为当天 00:00:00、until 展开为当天 23:59:59，
	 * 含边界当天）或 {@code yyyy-MM-dd HH:mm:ss}（原样）。空/空白返回 null（不筛选）；
	 * 非法格式抛 400，msg 指明参数名与合法格式。
	 *
	 * @param raw      原始参数值（可空）
	 * @param name     参数名（since/until，用于 400 提示）
	 * @param endOfDay yyyy-MM-dd 形式是否展开为当天 23:59:59（until 侧）
	 * @return 解析后的时间（null=不筛选）
	 */
	private Date parseTimeParam(String raw, String name, boolean endOfDay) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String v = raw.trim();
		try {
			if (v.length() == 10) {
				LocalDate d = LocalDate.parse(v);
				LocalDateTime ldt = endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
				return Timestamp.valueOf(ldt);
			}
			if (v.length() == 19) {
				return Timestamp.valueOf(LocalDateTime.parse(v, DATETIME_FORMATTER));
			}
		} catch (DateTimeParseException ignored) {
			// 落到下方统一 400
		}
		throw new BadRequestException(name + " 日期格式非法，须为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
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
