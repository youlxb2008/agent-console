package com.youlxb2008.admin.modules.notebook.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.modules.notebook.dto.NotebookAgentLinkReq;
import com.youlxb2008.admin.modules.notebook.dto.NotebookSaveReq;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;
import com.youlxb2008.admin.modules.notebook.vo.NotebookAgentVO;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端笔记本域 Controller（v3.5 新增，契约 api.md v4.0 §2.2）
 * <p>
 * 鉴权：JWT + {@code @RequiresPermissions}（notebook:list/create/edit/delete/agent:list/agent:link）。
 * 写操作（POST/PUT/DELETE）由 {@link com.youlxb2008.admin.utils.audit.AuditLogAspect}
 * 自动记入 t_sys_audit_log（与 NoticeController 保持一致）。
 * <p>
 * 职责：笔记本 CRUD（软删 D4）+ agent 关联维护（N:N 全量覆盖 replace）。
 */
@Slf4j
@RestController
@RequestMapping("/api/system/notebook")
@Tag(name = "管理端笔记本域", description = "JWT + @RequiresPermissions，笔记本 CRUD 与 agent 关联维护")
public class NotebookAdminController {

	@Autowired
	private NotebookService notebookService;

	@Autowired
	private AgentNotebookService agentNotebookService;

	// ------------------------------------------------------------
	// 笔记本 CRUD（notebook:list / create / edit / delete）
	// ------------------------------------------------------------

	/**
	 * GET /page — 笔记本分页（含 noteCount/agentCount 聚合，过滤 is_deleted=0）
	 */
	@Operation(summary = "笔记本分页（noteCount/agentCount 聚合）")
	@RequiresPermissions("notebook:list")
	@GetMapping("/page")
	public ResultVO page(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) String keyword) {
		return ResultVO.getSuccessVO("查询成功").setData(notebookService.pageVO(page, size, keyword));
	}

	/**
	 * GET /all — 未软删全量（管理端下拉选本）
	 */
	@Operation(summary = "笔记本下拉全量（未软删）")
	@RequiresPermissions("notebook:list")
	@GetMapping("/all")
	public ResultVO all() {
		List<NotebookVO> list = notebookService.listAll().stream().map(this::toVO).toList();
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * POST "" — 新建笔记本（name 唯一校验）
	 */
	@Operation(summary = "新建笔记本（name 唯一）")
	@RequiresPermissions("notebook:create")
	@PostMapping("")
	public ResultVO create(@RequestBody @Validated NotebookSaveReq req) {
		User user = UserContext.getUser();
		Notebook nb = notebookService.create(req.getName(), req.getDescription(), req.getCover(),
				req.getLogo(), req.getHero(), req.getStatus(), user != null ? user.getId() : null);
		Map<String, Object> data = new HashMap<>();
		data.put("id", nb.getId());
		data.put("name", nb.getName());
		data.put("status", nb.getStatus());
		return ResultVO.getSuccessVO("创建成功").setData(data);
	}

	/**
	 * PUT "" — 编辑笔记本（name 唯一校验，排除自身）
	 */
	@Operation(summary = "编辑笔记本（name 唯一，排除自身）")
	@RequiresPermissions("notebook:edit")
	@PutMapping("")
	public ResultVO update(@RequestBody @Validated NotebookSaveReq req) {
		if (req.getId() == null) {
			throw new BadRequestException("笔记本 id 不能为空");
		}
		Notebook nb = notebookService.update(req.getId(), req.getName(), req.getDescription(),
				req.getCover(), req.getLogo(), req.getHero(), req.getStatus());
		Map<String, Object> data = new HashMap<>();
		data.put("id", nb.getId());
		data.put("name", nb.getName());
		data.put("status", nb.getStatus());
		return ResultVO.getSuccessVO("更新成功").setData(data);
	}

	/**
	 * DELETE /{id} — 软删笔记本（D4：name 释放原名 + 物理删 t_agent_notebook 关联 + 保留 t_note_meta）
	 */
	@Operation(summary = "软删笔记本（释放原名 + 解除 agent 关联，笔记数据保留）")
	@RequiresPermissions("notebook:delete")
	@DeleteMapping("/{id}")
	public ResultVO delete(@PathVariable Long id) {
		notebookService.delete(id);
		return ResultVO.getSuccessVO("删除成功");
	}

	// ------------------------------------------------------------
	// agent 关联维护（notebook:agent:list / notebook:agent:link）
	// ------------------------------------------------------------

	/**
	 * GET /{id}/agents — 笔记本下已关联 agent 列表（含关联时间 linkTime）
	 */
	@Operation(summary = "笔记本下 agent 列表（含关联时间）")
	@RequiresPermissions("notebook:agent:list")
	@GetMapping("/{id}/agents")
	public ResultVO agents(@PathVariable Long id) {
		// 笔记本须存在（404 兜底，防对已删笔记本挂关联）
		if (notebookService.getByIdOrNull(id) == null) {
			return ResultVO.getFailedVO("笔记本不存在");
		}
		List<NotebookAgentVO> list = notebookService.listAgentsByNotebook(id);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * PUT /{id}/agents — agent 关联全量覆盖（目标集合即最终态，幂等 replace）
	 * <p>
	 * 不存在的 agentId 忽略；空数组=清空全部关联。
	 */
	@Operation(summary = "agent 关联全量覆盖（body=agentIds[]，幂等 replace）")
	@RequiresPermissions("notebook:agent:link")
	@PutMapping("/{id}/agents")
	public ResultVO updateAgents(@PathVariable Long id,
			@RequestBody(required = false) NotebookAgentLinkReq req) {
		if (notebookService.getByIdOrNull(id) == null) {
			return ResultVO.getFailedVO("笔记本不存在");
		}
		User user = UserContext.getUser();
		agentNotebookService.replaceByNotebook(id,
				req != null ? req.getAgentIds() : null,
				user != null ? user.getUsername() : "unknown");
		return ResultVO.getSuccessVO("关联保存成功");
	}

	// ------------------------------------------------------------
	// 私有转换
	// ------------------------------------------------------------

	private NotebookVO toVO(Notebook nb) {
		NotebookVO vo = new NotebookVO();
		vo.setId(nb.getId());
		vo.setName(nb.getName());
		vo.setDescription(nb.getDescription());
		vo.setCover(nb.getCover());
		vo.setLogo(nb.getLogo());
		vo.setHero(nb.getHero());
		vo.setStatus(nb.getStatus());
		vo.setCreateTime(nb.getCreateTime());
		vo.setUpdateTime(nb.getUpdateTime());
		return vo;
	}
}
