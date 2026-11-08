package com.youlxb2008.admin.modules.objective.controller;

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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.objective.dto.ObjectiveCreateReq;
import com.youlxb2008.admin.modules.objective.dto.ObjectivePatchReq;
import com.youlxb2008.admin.modules.objective.entity.Objective;
import com.youlxb2008.admin.modules.objective.service.ObjectiveService;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveDetailVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.BPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端目标域 Controller（M4.1，/api/objective/admin/objectives）
 * <p>
 * 鉴权：JWT + {@code @RequiresPermissions}（objective:list/create/edit/delete，菜单 SQL m4-menu.sql 已入库并授权 super_admin）。
 * 写操作（POST/PATCH/DELETE）由 AuditLogAspect 自动记入 t_sys_audit_log（与 notebook 域一致）；
 * 目标为 agent 私域，管理员可代建/代改（指定 agentId）。
 * 分层：注入 objective 域 Service + agent 域 AgentService（≤2 Service，Controller 禁直连 Mapper）。
 */
@Slf4j
@RestController
@RequestMapping("/api/objective/admin/objectives")
@Tag(name = "管理端目标域", description = "JWT + @RequiresPermissions，目标管理与按 agent 查询")
public class ObjectiveAdminController {

	@Autowired
	private ObjectiveService objectiveService;

	@Autowired
	private AgentService agentService;

	/**
	 * GET /api/objective/admin/objectives — 分页（keyword 搜 title / agentId 过滤）
	 */
	@Operation(summary = "目标分页（keyword 搜 title / agentId 过滤）")
	@RequiresPermissions("objective:list")
	@GetMapping
	public ResultVO page(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) Long agentId,
			@RequestParam(required = false) String keyword) {
		IPage<ObjectiveVO> result = objectiveService.pageSearch(page, size, agentId, keyword);
		return ResultVO.getSuccessVO("查询成功").setData(result);
	}

	/**
	 * POST /api/objective/admin/objectives — 管理员代建目标（agentId 必填，须存在否则 404）
	 * <p>
	 * agentId 走 query（目标主体不是本资源路径；RESTful 资源 ID 走路径约束不适用）。
	 */
	@Operation(summary = "管理员代建目标（agentId 必填；写 md 模板 + 索引 + 作者关联）")
	@RequiresPermissions("objective:create")
	@PostMapping
	public ResultVO create(@RequestParam Long agentId,
			@RequestBody @Validated ObjectiveCreateReq req) {
		if (agentService.getById(agentId) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		Objective obj = objectiveService.create(agentId, "admin", req);
		return ResultVO.getSuccessVO("创建成功")
				.setData(Map.of("oid", obj.getOid(), "title", obj.getTitle(), "progress",
						obj.getProgress(), "updateTime", obj.getUpdateTime()));
	}

	/**
	 * GET /api/objective/admin/objectives/{oid} — 目标详情（md 全文 + 子项解析）
	 */
	@Operation(summary = "目标详情（md 全文 + checkbox 子项解析）")
	@RequiresPermissions("objective:list")
	@GetMapping("/{oid}")
	public ResultVO detail(@PathVariable String oid) {
		ObjectiveDetailVO vo = objectiveService.getDetail(oid);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PATCH /api/objective/admin/objectives/{oid} — 管理员代改（title/period/targetDate/status/descMd）
	 */
	@Operation(summary = "管理员代改目标（改 descMd 覆写文件描述段并重算 progress）")
	@RequiresPermissions("objective:edit")
	@PatchMapping("/{oid}")
	public ResultVO patch(@PathVariable String oid, @RequestBody ObjectivePatchReq req) {
		// 管理端代改：无私域归属校验（管理员全量可见）；仅校验目标存在（404）
		Objective obj = objectiveService.patchMetaByAdmin(oid, req);
		return ResultVO.getSuccessVO("更新成功")
				.setData(Map.of("oid", obj.getOid(), "title", obj.getTitle(), "status",
						obj.getStatus(), "progress", obj.getProgress(), "updateTime",
						obj.getUpdateTime()));
	}

	/**
	 * DELETE /api/objective/admin/objectives/{oid} — 软删（is_deleted=1，md 保留）
	 */
	@Operation(summary = "软删目标（is_deleted=1，md 文件保留）")
	@RequiresPermissions("objective:delete")
	@DeleteMapping("/{oid}")
	public ResultVO delete(@PathVariable String oid) {
		objectiveService.deleteByAdmin(oid);
		return ResultVO.getSuccessVO("删除成功");
	}

	/**
	 * GET /api/objective/admin/agents/{agentId}/objectives — 按 agent 查目标列表
	 */
	@Operation(summary = "按 agent 查目标列表（agent 不存在 → 404）")
	@RequiresPermissions("objective:list")
	@GetMapping("/agents/{agentId}/objectives")
	public ResultVO listByAgent(@PathVariable Long agentId) {
		List<ObjectiveVO> list = objectiveService.listByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}
}
