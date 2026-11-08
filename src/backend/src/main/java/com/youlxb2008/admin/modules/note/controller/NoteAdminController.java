package com.youlxb2008.admin.modules.note.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;
import com.youlxb2008.admin.modules.note.dto.NoteAgentsUpdateReq;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.note.vo.AdminNoteContentVO;
import com.youlxb2008.admin.modules.note.vo.AdminNoteVO;
import com.youlxb2008.admin.modules.note.vo.NoteContentVO;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.context.UserContext;
import com.youlxb2008.admin.utils.page.BPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端笔记域 Controller（v3 新增，设计 §5.2）
 * <p>
 * 鉴权：JWT（{@code /api/note/admin/**} 不在 ApiKeyAuthFilter 的 /api/agent/ 拦截范围，走管理端 JWT）
 * + {@code @RequiresPermissions}（agent:note:list / delete / link，菜单 SQL 已入库并授权 super_admin）。
 * <p>
 * 职责：笔记管理（迁自 AgentAdminController 三接口）+ 关联维护（N:N 双向弹窗）。
 * 分层：注入 note 域 Service/Manager + agent 域 AgentResourceService/AuditService（≤3 Service）。
 */
@Slf4j
@RestController
@RequestMapping("/api/note/admin")
@Tag(name = "管理端笔记域", description = "JWT + @RequiresPermissions，笔记管理与 N:N 关联维护")
public class NoteAdminController {

	@Autowired
	private NoteMetaService noteMetaService;

	@Autowired
	private NoteStorageManager noteStorageManager;

	@Autowired
	private AgentResourceService agentResourceService;

	@Autowired
	private AuditService auditService;

	// ------------------------------------------------------------
	// 笔记管理（agent:note:list / agent:note:delete）
	// ------------------------------------------------------------

	/**
	 * GET /notes — 笔记索引分页（v3 迁自 GET /api/agent/admin/notes）
	 * <p>
	 * agentId 过滤走关联表（该 agent 关联的笔记）；keyword 搜 title LIKE；
	 * 列表项含 dirPath/contributors（🔥 不再返回 agentId/agentName 单值/filePath）。
	 */
	@Operation(summary = "笔记索引分页（agentId/notebookId 过滤；keyword 搜 title）")
	@RequiresPermissions("agent:note:list")
	@GetMapping("/notes")
	public ResultVO listNotes(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) Long agentId,
			@RequestParam(required = false) Long notebookId,
			@RequestParam(required = false) String keyword) {
		IPage<NoteMeta> result = noteMetaService.pageSearch(page, size, agentId, notebookId, keyword);
		IPage<AdminNoteVO> voPage = result.convert(this::toAdminNoteVO);
		return ResultVO.getSuccessVO("查询成功").setData(voPage);
	}

	/**
	 * GET /notes/{nid}/content — 笔记全文预览（v3 迁自原接口）
	 * <p>
	 * nid 定位读文件真相源；孤儿索引行（有行无文件）→ 404；
	 * 🔥 agentName 单值改为 contributors 多值。
	 */
	@Operation(summary = "笔记全文预览（nid 定位读文件，含 contributors）")
	@RequiresPermissions("agent:note:list")
	@GetMapping("/notes/{nid}/content")
	public ResultVO noteContent(@PathVariable String nid) {
		NoteContentVO content = noteStorageManager.readContent(nid);
		AdminNoteContentVO vo = new AdminNoteContentVO();
		vo.setNid(content.getNid());
		vo.setTitle(content.getTitle());
		vo.setContentMd(content.getContentMd());
		vo.setDirPath(content.getDirPath());
		vo.setUpdateTime(content.getUpdateTime());
		vo.setSizeBytes(content.getSizeBytes());
		vo.setContributors(agentResourceService.listNoteContributors(nid));
		return ResultVO.getSuccessVO("查询成功").setData(vo);
	}

	/**
	 * DELETE /notes/{nid} — 物理删除（文件 + 索引 + 全部关联行；审计 note.delete）
	 */
	@Operation(summary = "删除笔记（物理删文件+索引+全部关联，不可恢复）")
	@RequiresPermissions("agent:note:delete")
	@DeleteMapping("/notes/{nid}")
	public ResultVO deleteNote(@PathVariable String nid, HttpServletRequest request) {
		noteStorageManager.deleteFully(nid);
		audit(currentUser(), "note.delete", "/api/note/admin/notes/" + nid, "success", request);
		return ResultVO.getSuccessVO("删除成功");
	}

	/**
	 * GET /agents/{agentId}/notes — 该 agent 关联笔记分页（✨ v3 新增）
	 * <p>
	 * agent 管理页「笔记」弹窗数据源（查看 + 单条解除关联）。
	 */
	@Operation(summary = "agent 关联笔记分页（agent 管理页「笔记」弹窗数据源）")
	@RequiresPermissions("agent:note:list")
	@GetMapping("/agents/{agentId}/notes")
	public ResultVO agentNotes(@PathVariable Long agentId,
			@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) Long notebookId,
			@RequestParam(required = false) String keyword) {
		IPage<NoteMeta> result = noteMetaService.pageSearch(page, size, agentId, notebookId, keyword);
		IPage<AdminNoteVO> voPage = result.convert(this::toAdminNoteVO);
		return ResultVO.getSuccessVO("查询成功").setData(voPage);
	}

	// ------------------------------------------------------------
	// 关联维护（agent:note:link，双向弹窗共用）
	// ------------------------------------------------------------

	/**
	 * GET /notes/{nid}/agents — 该笔记已关联 agent（「关联 Agent」弹窗回显）
	 */
	@Operation(summary = "笔记已关联 agent 列表（关联弹窗回显）")
	@RequiresPermissions("agent:note:link")
	@GetMapping("/notes/{nid}/agents")
	public ResultVO noteAgents(@PathVariable String nid) {
		List<AgentSimpleVO> list = agentResourceService.listNoteContributors(nid);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * PUT /notes/{nid}/agents — 关联全量覆盖（目标集合即最终态；审计 note.link/note.unlink 差集）
	 * <p>
	 * 不存在的 agentId 忽略；空数组=清空全部关联（前端二次确认防误操作，设计 §8）。
	 */
	@Operation(summary = "笔记关联全量覆盖（差集审计 note.link/note.unlink）")
	@RequiresPermissions("agent:note:link")
	@PutMapping("/notes/{nid}/agents")
	public ResultVO updateNoteAgents(@PathVariable String nid,
			@RequestBody NoteAgentsUpdateReq req, HttpServletRequest request) {
		// 笔记须存在（404 兜底，防对已删笔记挂关联）
		if (noteMetaService.findByNid(nid) == null) {
			return ResultVO.getFailedVO("笔记不存在");
		}
		User user = currentUser();
		var diff = agentResourceService.replaceAgents(nid,
				req != null ? req.getAgentIds() : null,
				user != null ? user.getUsername() : "unknown");
		// 差集审计：新增→note.link，移除→note.unlink（各自逐条记录，可人工恢复）
		for (Long added : diff.added()) {
			audit(user, "note.link", nid + "/agent/" + added, "success", request);
		}
		for (Long removed : diff.removed()) {
			audit(user, "note.unlink", nid + "/agent/" + removed, "success", request);
		}
		return ResultVO.getSuccessVO("关联保存成功");
	}

	/**
	 * DELETE /notes/{nid}/agents/{agentId} — 单条解除（笔记与索引不动；审计 note.unlink）
	 * <p>
	 * agent 管理页「笔记」弹窗内逐条解除；未关联时幂等成功。
	 */
	@Operation(summary = "解除单条关联（笔记与其他 agent 关联不受影响）")
	@RequiresPermissions("agent:note:link")
	@DeleteMapping("/notes/{nid}/agents/{agentId}")
	public ResultVO unlinkAgent(@PathVariable String nid, @PathVariable Long agentId,
			HttpServletRequest request) {
		agentResourceService.unlink(agentId, com.youlxb2008.admin.modules.agent.entity.AgentResource.TYPE_NOTE, nid);
		audit(currentUser(), "note.unlink", nid + "/agent/" + agentId, "success", request);
		return ResultVO.getSuccessVO("解除关联成功");
	}

	// ------------------------------------------------------------
	// KB（知识库索引）：全量重建 + 进度查询
	// ------------------------------------------------------------

	@Autowired
	private com.youlxb2008.admin.modules.note.kb.service.KnowledgeIndexService knowledgeIndexService;

	/**
	 * POST /kb/rebuild — 全量重建知识库索引（分块 + 向量化 + Lucene，异步执行）
	 * <p>
	 * 适用场景：首次接入 / 配好百炼 key 后补向量 / 更换 embedding 模型 / 索引损坏。
	 * 立即返回，前端轮询 GET /kb/status 看进度；重建中重复触发返回失败。
	 */
	@Operation(summary = "全量重建知识库索引（异步；轮询 /kb/status 看进度）")
	@RequiresPermissions("agent:note:link")
	@PostMapping("/kb/rebuild")
	public ResultVO rebuildKb(HttpServletRequest request) {
		knowledgeIndexService.rebuildAll();
		audit(currentUser(), "kb.rebuild", "all", "success", request);
		return ResultVO.getSuccessVO("重建任务已启动");
	}

	/**
	 * GET /kb/status — 重建进度 + 依赖健康度（key 是否配置、Lucene 是否可用）
	 */
	@Operation(summary = "知识库索引状态（重建进度/key 配置/Lucene 可用性）")
	@RequiresPermissions("agent:note:list")
	@GetMapping("/kb/status")
	public ResultVO kbStatus() {
		return ResultVO.getSuccessVO("查询成功").setData(knowledgeIndexService.status());
	}

	// ------------------------------------------------------------
	// 私有转换/工具
	// ------------------------------------------------------------

	/**
	 * 索引行 → AdminNoteVO（contributors 逐条查：分页 size ≤50 且 JOIN 已走索引，
	 * 现规模（百级笔记）无 N+1 压力；慢查询时再改批量版本）
	 */
	private AdminNoteVO toAdminNoteVO(NoteMeta meta) {
		AdminNoteVO vo = new AdminNoteVO();
		vo.setNid(meta.getNid());
		vo.setNotebookId(meta.getNotebookId());
		vo.setNotebookName(meta.getNotebookName());
		vo.setTitle(meta.getTitle());
		vo.setDirPath(meta.getDirPath());
		vo.setTags(meta.getTags());
		vo.setUpdateTime(meta.getUpdateTime());
		vo.setContributors(agentResourceService.listNoteContributors(meta.getNid()));
		return vo;
	}

	private User currentUser() {
		return UserContext.getUser();
	}

	private void audit(User user, String action, String resource, String result, HttpServletRequest request) {
		auditService.append(user != null ? user.getUsername() : "unknown", action, resource,
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
