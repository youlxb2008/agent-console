package com.youlxb2008.admin.modules.agent.memory.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryWeightFloorReq;
import com.youlxb2008.admin.modules.agent.memory.service.MemoryService;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryDetailVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryStatsVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryVO;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端记忆 Controller（M7.1 M2，/api/memory/admin/**）
 * <p>
 * 鉴权：JWT + {@code @RequiresPermissions}（memory:list / archive / restore / delete，
 * 菜单 SQL m71-menu.sql 已入库并授权 super_admin）。
 * 写操作（POST/DELETE）由 AuditLogAspect 自动记入 t_sys_audit_log（与 objective 域一致）。
 * <p>
 * 路径口径：对齐仓库 /api/{module}/admin/** 惯例（任务书字面 /api/admin/** 会与
 * ApiKeyAuthFilter/RateLimitFilter 的 /api/agent/* 拦截段无关，但不符合仓库既有
 * 管理端路由风格，故按 /api/memory/admin/** 实现——MR 描述偏离项已注明）。
 * agentId 走路径（/agents/{agentId}/memories，RESTful 资源 ID 走路径约束）。
 * <p>
 * 分层：注入 memory 域 MemoryService + agent 域 AgentService（Controller 禁直连 Mapper）。
 */
@Slf4j
@RestController
@RequestMapping("/api/memory/admin")
@Tag(name = "管理端记忆域", description = "JWT + @RequiresPermissions，记忆管理与统计")
public class MemoryAdminController {

	@Autowired
	private MemoryService memoryService;

	@Autowired
	private AgentService agentService;

	/**
	 * GET /api/memory/admin/agents/{agentId}/memories — 按 agent 分页（status/memType/keyword 筛选）
	 */
	@Operation(summary = "按 agent 分页查询记忆（status/memType/keyword 筛选，回填 agentName）")
	@RequiresPermissions("memory:list")
	@GetMapping("/agents/{agentId}/memories")
	public ResultVO page(@PathVariable Long agentId,
			@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) Integer status,
			@RequestParam(required = false) String memType,
			@RequestParam(required = false) String keyword) {
		if (agentService.getById(agentId) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		IPage<MemoryVO> result = memoryService.pageForAdmin(agentId, status, memType, keyword, page, size);
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}

	/**
	 * GET /api/memory/admin/memories/stats — 各 agent 活跃/归档/被取代计数
	 */
	@Operation(summary = "记忆统计（各 agent 活跃/归档/被取代计数，统计卡片数据源）")
	@RequiresPermissions("memory:list")
	@GetMapping("/memories/stats")
	public ResultVO stats() {
		var result = memoryService.statsForAdmin();
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}

	/**
	 * GET /api/memory/admin/memories/{id} — 详情（contentMd 全文 + superseded 链回溯）
	 * <p>
	 * 任务书管理端 API 清单未列详情端点，但 §3.1 要求「详情抽屉（含 superseded 链）」，
	 * 链回溯数据仅详情接口返回，故补本端点（MR 描述偏离项已注明）。
	 */
	@Operation(summary = "记忆详情（contentMd 全文 + superseded 链回溯，详情抽屉数据源）")
	@RequiresPermissions("memory:list")
	@GetMapping("/memories/{id}")
	public ResultVO detail(@PathVariable Long id) {
		MemoryDetailVO vo = memoryService.detailByAdmin(id);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * POST /api/memory/admin/memories/{id}/archive — 归档（1→2，仅活跃可归档）
	 */
	@Operation(summary = "归档记忆（status 1→2；仅活跃状态可归档，被取代 3 不可）")
	@RequiresPermissions("memory:archive")
	@PostMapping("/memories/{id}/archive")
	public ResultVO archive(@PathVariable Long id) {
		MemoryVO vo = memoryService.archiveByAdmin(id);
		return ResultVO.getSuccessVO("归档成功").setData(vo);
	}

	/**
	 * POST /api/memory/admin/memories/{id}/restore — 恢复（2→1，仅归档可恢复）
	 */
	@Operation(summary = "恢复记忆（status 2→1；仅归档状态可恢复，与 agent 域口径一致）")
	@RequiresPermissions("memory:restore")
	@PostMapping("/memories/{id}/restore")
	public ResultVO restore(@PathVariable Long id) {
		MemoryVO vo = memoryService.restoreByAdmin(id);
		return ResultVO.getSuccessVO("恢复成功").setData(vo);
	}

	/**
	 * PUT /api/memory/admin/memories/{id}/weight-floor — 调整权重保底线（M7.1-M3a）。
	 * 权限码复用 memory:archive（同为记忆管理写操作；避免动已落库菜单 SQL，MR 描述偏离项已注明）。
	 */
	@Operation(summary = "调整记忆权重保底线（0-100，0=不保底；衰减重算落库 weight = max(公式值, floor)）")
	@RequiresPermissions("memory:archive")
	@PutMapping("/memories/{id}/weight-floor")
	public ResultVO updateWeightFloor(@PathVariable Long id,
			@RequestBody @Validated MemoryWeightFloorReq req) {
		MemoryVO vo = memoryService.updateWeightFloorByAdmin(id, req.getWeightFloor());
		return ResultVO.getSuccessVO("更新成功").setData(vo);
	}

	/**
	 * DELETE /api/memory/admin/memories/{id} — 删除（软删 is_deleted=1 + 移除索引）
	 */
	@Operation(summary = "删除记忆（逻辑删除 is_deleted=1；索引文档同步移除）")
	@RequiresPermissions("memory:delete")
	@DeleteMapping("/memories/{id}")
	public ResultVO delete(@PathVariable Long id) {
		memoryService.deleteByAdmin(id);
		return ResultVO.getSuccessVO("删除成功");
	}
}
