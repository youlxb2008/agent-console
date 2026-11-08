package com.youlxb2008.admin.modules.agent.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.entity.AuditLog;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.AttachmentUploadVO;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.modules.agent.vo.MeVO;
import com.youlxb2008.admin.modules.note.dto.NotePatchReq;
import com.youlxb2008.admin.modules.note.dto.NoteWriteReq;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.kb.service.KnowledgeSearchService;
import com.youlxb2008.admin.modules.note.kb.vo.KbAskVO;
import com.youlxb2008.admin.modules.note.kb.vo.KbHitVO;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.note.vo.NoteTreeNode;
import com.youlxb2008.admin.modules.note.vo.NoteUpsertVO;
import com.youlxb2008.admin.modules.notebook.dto.NotebookSaveReq;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;
import com.youlxb2008.admin.modules.system.service.FileUploadService;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.page.BPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域 API Controller（v3：智能覆写 + N:N 关联，内部调 note 域）
 * <p>
 * 鉴权：Header {@code X-API-Key}（ApiKeyAuthFilter 认证）+ {@code @RequireScope}（scope 校验）。
 * 每次调用（含 denied）写审计：denied 由 ScopeAspect 记录，success 由本类记录；
 * 一致性失败（indexOk/linkOk=false / delete 异常）记 result=error。
 * <p>
 * v3 语义（设计 §5.1）：
 * <ul>
 *   <li>upsert：智能覆写锚点 (dir_path, title) + hasLink——未找到/未关联自己=新建，已关联=覆写</li>
 *   <li>delete：须已关联（防跨 agent 误删），删文件+索引+全部关联行</li>
 *   <li>tree：数据驱动（关联过滤 + dir_path 内存聚合），不再扫盘</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@Tag(name = "agent 域 API", description = "X-API-Key 认证，scopes 校验")
public class AgentApiController {

	/** q 关键词长度上限（与门户 /kb/search 同口径，契约 §1.10） */
	private static final int MAX_KEYWORD_LEN = 100;

	@Autowired
	private NoteStorageManager noteStorageManager;

	@Autowired
	private NoteMetaService noteMetaService;

	@Autowired
	private AgentResourceService agentResourceService;

	@Autowired
	private AuditService auditService;

	@Autowired
	private AgentService agentService;

	/** v3.5：agent 关联笔记本（D5 校验 / GET /notebooks） */
	@Autowired
	private AgentNotebookService agentNotebookService;

	/** M5.1：统一文件上传服务（复用既有白名单/日期目录/文件名生成，不重造） */
	@Autowired
	private FileUploadService fileUploadService;

	/** v3.5：笔记本档案（GET /notebooks 组装） */
	@Autowired
	private NotebookService notebookService;

	/**
	 * 1.1 写/覆写笔记（scope: notes:write）——v3 智能覆写 + v3.5 D5 校验链
	 * <p>
	 * 入参含 must notebookId；返回 {nid, notebookId, dirPath, title, updateTime}，
	 * 覆写返回原 nid（不变）、新建返回新 nid（供 agent 核对，§8 同名歧义对策）。
	 */
	@Operation(summary = "写/覆写笔记（D5 校验：须已关联目标笔记本，否则 403）")
	@RequireScope(value = "notes:write", action = "note.write")
	@PostMapping("/notes")
	public ResultVO upsertNote(@RequestBody @Validated NoteWriteReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		// D5 校验链（fail-closed）：notebook 存在且未软删（404）→ 当前 agent 已关联（403）
		noteMetaService.validateNotebookWrite(identity.getAgentId(), req.getNotebookId());
		NoteStorageManager.UpsertResult result = noteStorageManager.upsert(identity.getAgentId(),
				identity.getAgentName(), req.getNotebookId(), req.getDirPath(), req.getTitle(),
				req.getContentMd(), req.getTags());
		// 一致性失败（索引或创建者关联未写成功）记审计 error，响应仍成功（文件已写入）
		boolean consistent = result.isIndexOk() && result.isLinkOk();
		audit(identity, "note.write", result.getNid() + "|notebook=" + req.getNotebookId(),
				consistent ? "success" : "error", request);
		NoteUpsertVO vo = new NoteUpsertVO(result.getNid(), req.getNotebookId(), result.getDirPath(),
				result.getTitle(), result.getUpdateTime());
		return ResultVO.getSuccessVO(result.isCreated() ? "创建成功" : "更新成功").setData(vo);
	}

	/**
	 * M3.8 旧路径占位：DELETE /notes?nid= 已下线（RESTful 化，nid 走路径 /notes/{nid}）。
	 * <p>
	 * 为什么需要占位：/notes 仍映射 POST（upsert），DELETE 无 handler 时 Spring 抛
	 * {@code HttpRequestMethodNotSupportedException} 会被全局 catch-all 吞成 500；这里显式占位
	 * 返回真实 404，还原「下线即 404」语义（T_rest_12：旧路径 DELETE /api/agent/notes?nid= → 404，
	 * 且绝不返回假 200/假页面——nginx try_files 前必须真 404）。
	 */
	@Operation(summary = "旧路径 DELETE /notes?nid= 已下线（404）")
	@DeleteMapping("/notes")
	public ResultVO deleteNoteOldPathGone() {
		throw new NotFoundException("旧路径 DELETE /notes?nid= 已下线，请改用 DELETE /notes/{nid}");
	}

	/**
	 * 1.2 删除笔记（scope: notes:write）——v3 须已关联（hasLink），删文件+索引+全部关联行
	 * <p>
	 * 未关联（含笔记不存在）→ 404（防枚举）；关联校验取代 v2 的 agent_id 归属比对。
	 * M3.8：RESTful——nid 走路径（DELETE /notes/{nid}），废除旧 ?nid= 形态。
	 */
	@Operation(summary = "删除笔记（须已关联；物理删文件+索引+全部关联行）")
	@RequireScope(value = "notes:write", action = "note.delete")
	@DeleteMapping("/notes/{nid}")
	public ResultVO deleteNote(@PathVariable String nid, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		// 关联校验：仅已关联（贡献者）可删；未关联/不存在统一 404 防枚举
		NoteMeta meta = noteMetaService.findByNid(nid);
		if (meta == null || !agentResourceService.hasLink(identity.getAgentId(),
				AgentResource.TYPE_NOTE, nid)) {
			throw new NotFoundException("笔记不存在");
		}
		try {
			noteStorageManager.deleteFully(nid);
		} catch (Exception e) {
			audit(identity, "note.delete", nid + "|notebook=" + meta.getNotebookId(), "error", request);
			throw e;
		}
		audit(identity, "note.delete", nid + "|notebook=" + meta.getNotebookId(), "success", request);
		return ResultVO.getSuccessVO("删除成功");
	}

	/**
	 * 1.7 挪动/重命名笔记（scope: notes:write，M3.7）——按 nid 改 meta，保留 nid/文件/关联
	 * <p>
	 * 校验链见 {@link NoteMetaService#patchMeta}：未关联/不存在 404（防枚举）、改 notebookId 走 D5（404/403）、
	 * 冲突 422、全空 400；updateById 只改请求出现的字段，update_time 刷新；不动文件、不动关联行。
	 */
	@Operation(summary = "挪动/重命名笔记（按 nid 改 meta；保留 nid/文件/关联行）")
	@RequireScope(value = "notes:write", action = "note.update")
	@PatchMapping("/notes/{nid}")
	public ResultVO patchNote(@PathVariable String nid, @RequestBody @Validated NotePatchReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		try {
			NoteMeta meta = noteMetaService.patchMeta(identity.getAgentId(), nid,
					req.getNotebookId(), req.getDirPath(), req.getTitle());
			audit(identity, "note.update", nid + "|notebook=" + meta.getNotebookId(), "success", request);
			NoteUpsertVO vo = new NoteUpsertVO(meta.getNid(), meta.getNotebookId(), meta.getDirPath(),
					meta.getTitle(), meta.getUpdateTime());
			return ResultVO.getSuccessVO("更新成功").setData(vo);
		} catch (Exception e) {
			audit(identity, "note.update", nid, "error", request);
			throw e;
		}
	}

	/**
	 * 1.3 关联笔记本下笔记的目录树（scope: notes:write，写入后自校验用）
	 * <p>
	 * v3.5 语义变更：返回当前 agent 关联的所有笔记本下笔记的树（不再按作者身份）；
	 * 未关联任何 notebook 返回空树。节点带 notebookId/notebookName（前端分组渲染）。
	 */
	@Operation(summary = "关联笔记本下笔记的目录树（按关联 notebook 聚合，节点带 notebookId/notebookName）")
	@RequireScope(value = "notes:write", action = "note.write")
	@GetMapping("/notes/tree")
	public ResultVO tree(HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		List<NoteTreeNode> tree = noteMetaService
				.buildTree(noteMetaService.listByAgentNotebooks(identity.getAgentId()));
		audit(identity, "note.write", "tree", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(tree);
	}

	/**
	 * 1.3a 当前 agent 关联的笔记本列表（v3.5 新增，scope: notes:write，写笔记选本）
	 * <p>
	 * 返回 id/name/description/cover/status/createTime；未关联任何笔记本时为空列表
	 * （前端引导"请管理员关联笔记本"）。
	 */
	@Operation(summary = "当前 agent 关联的笔记本列表（写笔记选本）")
	@RequireScope(value = "notes:write", action = "note.write")
	@GetMapping("/notebooks")
	public ResultVO notebooks(HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		List<Long> ids = agentNotebookService.listNotebookIdsByAgent(identity.getAgentId());
		List<NotebookVO> list = ids.isEmpty() ? List.of()
				: notebookService.listByIds(ids).stream()
						.filter(nb -> nb != null)
						.map(this::toNotebookVO)
						.collect(Collectors.toList());
		audit(identity, "note.write", "notebooks", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(list);
	}

	/**
	 * 1.3b 创建笔记本（v3.5 方案 B，scope: notebook:manage）
	 * <p>
	 * 创建后自动关联自己（否则 D5 写校验不过，创建即不可用）；
	 * 管理端与 agent 侧共享 NotebookService（name 唯一、status 缺省 1）。
	 */
	@Operation(summary = "创建笔记本（scope: notebook:manage；创建后自动关联自己）")
	@RequireScope(value = "notebook:manage", action = "notebook.create")
	@PostMapping("/notebooks")
	public ResultVO createNotebook(@RequestBody @Validated NotebookSaveReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Notebook nb = notebookService.create(req.getName(), req.getDescription(), req.getCover(),
				req.getLogo(), req.getHero(), req.getStatus(), identity.getAgentId());
		// 创建后自动关联自己（幂等：已关联则忽略）
		agentNotebookService.link(identity.getAgentId(), nb.getId(), identity.getAgentName());
		audit(identity, "notebook.create", nb.getId() + "|name=" + nb.getName(), "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", nb.getId());
		data.put("name", nb.getName());
		data.put("status", nb.getStatus());
		return ResultVO.getSuccessVO("创建成功").setData(data);
	}

	/**
	 * 1.3c 编辑笔记本（v3.5 方案 B，scope: notebook:manage）
	 * <p>
	 * M3.8：RESTful——id 走路径（PUT /notebooks/{id}），body 不再携带 id，service 用 path id 覆盖。
	 */
	@Operation(summary = "编辑笔记本（scope: notebook:manage；name 唯一，排除自身）")
	@RequireScope(value = "notebook:manage", action = "notebook.edit")
	@PutMapping("/notebooks/{id}")
	public ResultVO updateNotebook(@PathVariable Long id, @RequestBody @Validated NotebookSaveReq req,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Notebook nb = notebookService.update(id, req.getName(), req.getDescription(),
				req.getCover(), null, null, req.getStatus());
		audit(identity, "notebook.edit", nb.getId() + "|name=" + nb.getName(), "success", request);
		Map<String, Object> data = new HashMap<>();
		data.put("id", nb.getId());
		data.put("name", nb.getName());
		data.put("status", nb.getStatus());
		return ResultVO.getSuccessVO("更新成功").setData(data);
	}

	/**
	 * 1.3d 软删笔记本（v3.5 方案 B，scope: notebook:manage）
	 * <p>
	 * 复用 NotebookService.delete（D4：释放原名 + 级联清 agent 关联 + 保留 t_note_meta）。
	 */
	@Operation(summary = "软删笔记本（scope: notebook:manage；笔记数据保留）")
	@RequireScope(value = "notebook:manage", action = "notebook.delete")
	@DeleteMapping("/notebooks/{id}")
	public ResultVO deleteNotebook(@PathVariable Long id, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		notebookService.delete(id);
		audit(identity, "notebook.delete", id + "", "success", request);
		return ResultVO.getSuccessVO("删除成功");
	}

	private NotebookVO toNotebookVO(Notebook nb) {
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

	// ============================================================
	// KB（知识库）：混合检索 + RAG 问答（v4.1 新增）
	// <p>
	// 权限模型：不引入新 scope，<b>复用 agent↔笔记本关联（t_agent_notebook）</b>——
	// 指定 notebookId → 须已关联（未关联 403）；省略 notebookId → 自动收敛到
	// 该 agent 关联的笔记本范围（数据隔离，绝不全库检索）。
	// 任意有效 key 可调（与 /me 一致，认证由 ApiKeyAuthFilter 保证）。
	// ============================================================

	@Autowired
	private KnowledgeSearchService knowledgeSearchService;

	/**
	 * 1.10 GET /kb/search — 知识库混合检索（标题 + BM25 全文 + 语义向量，RRF 融合）
	 * <p>
	 * q 必填 ≤100；notebookId 可选（省略=关联范围内检索）；limit 默认 10、钳制 ≤20。
	 * 索引未建/key 未配置时自动降级（BM25/语义路跳过，仅标题路），不报错。
	 */
	@Operation(summary = "知识库混合检索（标题+BM25+语义；权限=笔记本关联，省略 notebookId 时仅搜关联范围）")
	@GetMapping("/kb/search")
	public ResultVO kbSearch(@RequestParam String q,
			@RequestParam(required = false) Long notebookId,
			@RequestParam(required = false, defaultValue = "10") Integer limit,
			HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		// 入参校验（与门户 /kb/search 同口径）
		if (q == null || q.isBlank()) {
			throw new BadRequestException("q 不能为空");
		}
		if (q.trim().length() > MAX_KEYWORD_LEN) {
			throw new BadRequestException("q 长度不能超过 " + MAX_KEYWORD_LEN);
		}
		// 权限解析（复用 notebookId 关联，fail-closed）：未关联任何笔记本 → 空范围 → 空结果
		List<Long> scopeNotebooks;
		String resource = "notebook=" + (notebookId == null ? "linked" : notebookId);
		try {
			scopeNotebooks = resolveKbScope(identity.getAgentId(), notebookId);
		} catch (ForbiddenException e) {
			// 越权探测留痕：与 ScopeAspect 的 denied 口径一致，便于安全审计回溯
			audit(identity, "kb.search", resource, "denied", request);
			throw e;
		}
		List<KbHitVO> hits = knowledgeSearchService.hybridSearch(q, scopeNotebooks,
				limit == null ? 10 : limit);
		audit(identity, "kb.search", resource + "|hits=" + hits.size(), "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(hits);
	}

	/**
	 * 1.11 POST /kb/ask — 知识库 RAG 问答（检索 top6 片段 → qwen 生成答案 + 引用列表）
	 * <p>
	 * body：{question 必填, notebookId 可选}；范围语义与 /kb/search 一致（复用笔记本关联）；
	 * 未配置百炼 key → 400；未检索到相关内容 → 固定话术拒答（不烧 token）。
	 */
	@Operation(summary = "知识库 RAG 问答（附引用笔记列表；权限=笔记本关联，省略 notebookId 时仅搜关联范围）")
	@PostMapping("/kb/ask")
	public ResultVO kbAsk(@RequestBody KbAskReq req, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		if (req.getQuestion() == null || req.getQuestion().isBlank()) {
			throw new BadRequestException("question 不能为空");
		}
		// 权限解析与 /kb/search 完全一致（同一 resolveKbScope，保证两接口权限口径统一）
		String resource = "notebook=" + (req.getNotebookId() == null ? "linked" : req.getNotebookId());
		List<Long> scopeNotebooks;
		try {
			scopeNotebooks = resolveKbScope(identity.getAgentId(), req.getNotebookId());
		} catch (ForbiddenException e) {
			// 越权探测留痕：与 ScopeAspect 的 denied 口径一致，便于安全审计回溯
			audit(identity, "kb.ask", resource, "denied", request);
			throw e;
		}
		try {
			KbAskVO vo = knowledgeSearchService.ask(req.getQuestion(), scopeNotebooks);
			audit(identity, "kb.ask", resource, "success", request);
			return ResultVO.getSuccessVO("查询成功").setData(vo);
		} catch (Exception e) {
			// 模型调用失败等异常：记 error 审计后原样抛出（全局异常处理器统一返回）
			audit(identity, "kb.ask", resource, "error", request);
			throw e;
		}
	}

	/**
	 * KB 读权限解析（复用 agent↔笔记本关联，fail-closed）——search/ask 两接口共用
	 * <ul>
	 *   <li>notebookId 非空：须已关联（与 D5 写校验同源的关联判断），未关联 → 403；</li>
	 *   <li>notebookId 为空：返回该 agent 关联的全部笔记本 id（空集合=未关联任何笔记本，
	 *       检索层守卫为空结果，绝不退化成全库检索）。</li>
	 * </ul>
	 *
	 * @param agentId    当前 agent id
	 * @param notebookId 请求指定的范围笔记本（可空）
	 * @return 本次检索可见的笔记本 id 集合（空集合=无权限范围）
	 */
	private List<Long> resolveKbScope(Long agentId, Long notebookId) {
		if (notebookId != null) {
			// 指定笔记本：已关联才放行（403 与 ScopeAspect 拒绝口径一致）
			if (!agentNotebookService.hasLink(agentId, notebookId)) {
				throw new ForbiddenException("无该笔记本的知识库访问权限（未关联 notebook=" + notebookId + "）");
			}
			return List.of(notebookId);
		}
		// 省略 notebookId：数据隔离——只在关联笔记本范围内检索
		return agentNotebookService.listNotebookIdsByAgent(agentId);
	}

	/** /kb/ask 请求体 */
	@lombok.Data
	public static class KbAskReq {
		/** 用户问题（必填） */
		private String question;
		/** 范围笔记本（可选；空=当前 agent 全部关联笔记本） */
		private Long notebookId;
	}

	/**
	 * 1.8 图片上传（scope: notes:write，M5.1）——笔记配图
	 * <p>
	 * type 固定服务端拼 {@code agent-{agentId}}，不接受客户端传值（type 直接拼进存储路径，
	 * 客户端可控即路径注入点）；复用 FileUploadService 既有白名单（png/jpg/jpeg/gif/webp），
	 * 不扩白名单；不建附件表（文件系统为真相源，v3 哲学），不做删除/孤儿清理。
	 * <p>
	 * 失败处理：非图片/超限 → IllegalArgumentException → 全局 400；上传 IO 失败 → 500。
	 */
	@Operation(summary = "图片上传（笔记配图，type 服务端固定 agent-{id}）")
	@RequireScope(value = "notes:write", action = "attachment.upload")
	@PostMapping("/attachments")
	public ResultVO uploadAttachment(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Map<String, String> uploaded = fileUploadService.uploadFile(file, "agent-" + identity.getAgentId());
		String url = uploaded.get("url");
		audit(identity, "attachment.upload", url, "success", request);
		AttachmentUploadVO vo = new AttachmentUploadVO(url, file.getOriginalFilename(), file.getSize());
		return ResultVO.getSuccessVO("上传成功").setData(vo);
	}

	/**
	 * 1.4 自己的审计记录（scope: audit:read，不变）
	 */
	@Operation(summary = "自己的审计记录")
	@RequireScope(value = "audit:read", action = "audit.list")
	@GetMapping("/audit")
	public ResultVO listAudit(@RequestParam(defaultValue = "1") long page,
			@RequestParam(defaultValue = "10") long size,
			@RequestParam(required = false) String action, HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		IPage<AuditLog> result = auditService.pageQuery(page, size, identity.getAgentName(), action, null);
		audit(identity, "audit.list", "/api/agent/audit", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(BPage.of(result));
	}

	/**
	 * 1.5 识别自己（任意有效 key，不变）
	 */
	@Operation(summary = "识别自己（自检 key 是否有效）")
	@GetMapping("/me")
	public ResultVO me(HttpServletRequest request) {
		KeyIdentity identity = AgentContext.get();
		Agent agent = agentService.getById(identity.getAgentId());
		MeVO vo = new MeVO();
		vo.setAgentId(identity.getAgentId());
		vo.setName(identity.getAgentName());
		vo.setDisplayName(agent != null ? agent.getDisplayName() : null);
		vo.setScopes(identity.getScopes());
		audit(identity, "auth.me", "/api/agent/me", "success", request);
		return ResultVO.getSuccessVO("查询成功").setData(vo);
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
