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

import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.ledger.dto.LedgerCreateReq;
import com.youlxb2008.admin.modules.ledger.dto.LedgerPatchReq;
import com.youlxb2008.admin.modules.ledger.entity.Ledger;
import com.youlxb2008.admin.modules.ledger.service.LedgerService;
import com.youlxb2008.admin.modules.ledger.vo.LedgerDetailVO;
import com.youlxb2008.admin.modules.ledger.vo.LedgerVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.BPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端台账域 Controller（M4.2，/api/ledger/admin/ledger）
 * <p>
 * 鉴权：JWT + {@code @RequiresPermissions}（ledger:list/create/edit/delete，菜单 SQL m4-menu.sql 已入库并授权 super_admin）。
 * 写操作（POST/PATCH/DELETE）由 AuditLogAspect 自动记入 t_sys_audit_log（与 objective/notebook 域一致）；
 * 台账为 agent 私域流水，管理员可代记/代改（指定 agentId）。
 * 分页返回 BPage（curPage/count/pageSize/list，任务书口径对齐 objective 模块）。
 * 分层：注入 ledger 域 Service + agent 域 AgentService（≤2 Service，Controller 禁直连 Mapper）。
 */
@Slf4j
@RestController
@RequestMapping("/api/ledger/admin/ledger")
@Tag(name = "管理端台账域", description = "JWT + @RequiresPermissions，台账管理与按 agent 查询")
public class LedgerAdminController {

	@Autowired
	private LedgerService ledgerService;

	@Autowired
	private AgentService agentService;

	/**
	 * GET /api/ledger/admin/ledger — 分页（agentId / type 筛选）
	 */
	@Operation(summary = "台账分页（agentId / type 筛选）")
	@RequiresPermissions("ledger:list")
	@GetMapping
	public ResultVO page(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) Long agentId,
			@RequestParam(required = false) String type) {
		com.baomidou.mybatisplus.core.metadata.IPage<LedgerVO> result = ledgerService
				.pageSearch(page, size, agentId, type);
		return ResultVO.getSuccessVO("查询成功").setData(BPage.of(result));
	}

	/**
	 * POST /api/ledger/admin/ledger — 管理员代记（agentId 必填，须存在否则 404）
	 * <p>
	 * agentId 走 query（台账主体不是本资源路径；RESTful 资源 ID 走路径约束不适用，同 objective 域）。
	 */
	@Operation(summary = "管理员代记台账（agentId 必填；body 双轨切分）")
	@RequiresPermissions("ledger:create")
	@PostMapping
	public ResultVO create(@RequestParam Long agentId,
			@RequestBody @Validated LedgerCreateReq req) {
		if (agentService.getById(agentId) == null) {
			return ResultVO.getFailedVO("agent 不存在");
		}
		Ledger row = ledgerService.create(agentId, "admin", req);
		return ResultVO.getSuccessVO("记录成功")
				.setData(Map.of("lid", row.getLid(), "type", row.getType(), "title", row.getTitle(),
						"createTime", row.getCreateTime()));
	}

	/**
	 * GET /api/ledger/admin/ledger/{lid} — 台账详情（读正文：inline 优先，否则读 md）
	 */
	@Operation(summary = "台账详情（读正文：inline 优先，否则读 md）")
	@RequiresPermissions("ledger:list")
	@GetMapping("/{lid}")
	public ResultVO detail(@PathVariable String lid) {
		LedgerDetailVO vo = ledgerService.getDetail(lid);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * PATCH /api/ledger/admin/ledger/{lid} — 管理员代改（type/title/body/ref 四列）
	 */
	@Operation(summary = "管理员代改台账（改 body 按双轨重切分）")
	@RequiresPermissions("ledger:edit")
	@PatchMapping("/{lid}")
	public ResultVO patch(@PathVariable String lid, @RequestBody LedgerPatchReq req) {
		// 管理端代改：无私域归属校验（管理员全量可见）；仅校验台账存在（404）
		Ledger row = ledgerService.patchMetaByAdmin(lid, req);
		return ResultVO.getSuccessVO("更新成功")
				.setData(Map.of("lid", row.getLid(), "type", row.getType(), "title", row.getTitle()));
	}

	/**
	 * DELETE /api/ledger/admin/ledger/{lid} — 软删（is_deleted=1，md 保留）
	 */
	@Operation(summary = "软删台账（is_deleted=1，长正文 md 文件保留）")
	@RequiresPermissions("ledger:delete")
	@DeleteMapping("/{lid}")
	public ResultVO delete(@PathVariable String lid) {
		ledgerService.deleteByAdmin(lid);
		return ResultVO.getSuccessVO("删除成功");
	}

	/**
	 * GET /api/ledger/admin/agents/{agentId}/ledger — 按 agent 查台账流
	 */
	@Operation(summary = "按 agent 查台账流（agent 不存在 → 404）")
	@RequiresPermissions("ledger:list")
	@GetMapping("/agents/{agentId}/ledger")
	public ResultVO listByAgent(@PathVariable Long agentId) {
		List<LedgerVO> list = ledgerService.listByAgent(agentId);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}
}
