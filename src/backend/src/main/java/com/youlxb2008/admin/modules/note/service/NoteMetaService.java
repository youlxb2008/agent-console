package com.youlxb2008.admin.modules.note.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.exceptions.UnprocessableEntityException;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.vo.TagCountVO;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.mapper.NoteMetaMapper;
import com.youlxb2008.admin.modules.note.vo.NoteTreeNode;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;

/**
 * 笔记索引 Service（v3 迁入 note 域，无主化改造）
 * <p>
 * 纯索引表 CRUD 与查询族：nid 定位 / (dir_path, title) 智能覆写锚点 /
 * 管理端分页搜索 / dir_path 内存建树 / 门户聚合。
 * v3 核心变化：agent 维度过滤全部改走 t_agent_resource 关联表
 * （先取 nid 集合再 IN 过滤，不在本域写反向 JOIN SQL）；
 * count/max 聚合迁往 {@link AgentResourceService}。
 */
@Service
public class NoteMetaService extends ServiceImpl<NoteMetaMapper, NoteMeta> {

	/** 门户聚合接口结果上限（契约：limit 最大 50） */
	private static final int MAX_RESULT_LIMIT = 50;

	@Autowired
	private AgentResourceService agentResourceService;

	/** KB 索引钩子：PATCH 改名/挪动后发事件重建该篇检索索引 */
	@Autowired
	private org.springframework.context.ApplicationEventPublisher eventPublisher;

	/** D5 写权限校验 / agent 关联笔记本查询（v3.5） */
	@Autowired
	private AgentNotebookService agentNotebookService;

	/**
	 * dirPath/title 规范化与校验复用（M3.7 PATCH 挪动/重命名）。
	 * NoteStorageManager 已注入本服务（findByNid/updateById 等），此处反向注入构成循环依赖，
	 * Spring 2.6+ 默认禁止循环引用 → 用 @Lazy 代理打破（两 bean 仅运行时方法调用互依，无初始化期依赖）
	 */
	@Autowired
	@Lazy
	private NoteStorageManager noteStorageManager;

	/** 笔记本存在性校验 / 笔记本名回填（v3.5） */
	@Autowired
	private NotebookService notebookService;

	/**
	 * 按 nid 查索引行（对外引用键，唯一）
	 *
	 * @param nid 对外引用键（n_ + 10 位 base62）
	 * @return 索引行；不存在返回 null
	 */
	public NoteMeta findByNid(String nid) {
		if (nid == null || nid.isBlank()) {
			return null;
		}
		return getOne(Wrappers.<NoteMeta>lambdaQuery().eq(NoteMeta::getNid, nid), true);
	}

	/**
	 * 按智能覆写锚点查索引行（dir_path + title，v3 新增）
	 * <p>
	 * 设计 §2.2：同目录同名多篇并存（协作场景天然产生）时，
	 * 取 update_time 最新一篇作为覆写对象（§8 同名歧义对策）。
	 *
	 * @param dirPath 目录路径（根目录为空串；null 规范化为空串）
	 * @param title   笔记标题
	 * @return 最新一篇索引行；不存在返回 null
	 */
	public NoteMeta findByDirAndTitle(String dirPath, String title) {
		String dir = dirPath == null ? "" : dirPath;
		return getOne(Wrappers.<NoteMeta>lambdaQuery()
				.eq(NoteMeta::getDirPath, dir)
				.eq(NoteMeta::getTitle, title)
				.orderByDesc(NoteMeta::getUpdateTime)
				.last("limit 1"), true);
	}

	/**
	 * 某 agent 关联的全部索引行（v3：关联表过滤；tree/管理端 agent 笔记弹窗数据源）
	 *
	 * @param agentId agent id
	 * @return 索引行列表（update_time 降序）
	 */
	public List<NoteMeta> listByAgent(Long agentId) {
		List<String> nids = agentResourceService.listResourceIdsByAgent(agentId, AgentResource.TYPE_NOTE);
		if (nids.isEmpty()) {
			return new ArrayList<>();
		}
		return listSorted(Wrappers.<NoteMeta>lambdaQuery().in(NoteMeta::getNid, nids));
	}

	/**
	 * agent 关联的所有笔记本下的笔记（v3.5 新增，tree 语义：按关联笔记本→直接 WHERE notebook_id）
	 * <p>
	 * 取 agent 关联 notebookId 集合 → 空则返回空 → t_note_meta WHERE notebook_id IN(...)。
	 * 同时回填每篇笔记的 notebookName（联查 t_notebook）。未关联任何笔记本时返回空树。
	 *
	 * @param agentId agent id
	 * @return 该 agent 可读可写范围内笔记本的笔记（update_time 降序）
	 */
	public List<NoteMeta> listByAgentNotebooks(Long agentId) {
		List<Long> notebookIds = agentNotebookService.listNotebookIdsByAgent(agentId);
		if (notebookIds.isEmpty()) {
			return new ArrayList<>();
		}
		List<NoteMeta> metas = listSorted(Wrappers.<NoteMeta>lambdaQuery()
				.in(NoteMeta::getNotebookId, notebookIds));
		fillNotebookNames(metas);
		return metas;
	}

	/**
	 * D5 写笔记校验链（fail-closed）：
	 * <ol>
	 * <li>notebookId 非空；notebook 存在且未软删（否则 {@link NotFoundException} 404）；</li>
	 * <li>当前 agent 在 t_agent_notebook 已关联该 notebook（否则 {@link ForbiddenException}
	 * 403）。</li>
	 * </ol>
	 *
	 * @param agentId    写入 agent id
	 * @param notebookId 目标笔记本 id
	 */
	public void validateNotebookWrite(Long agentId, Long notebookId) {
		if (notebookId == null) {
			throw new BadRequestException("notebookId 不能为空");
		}
		// @TableLogic 自动过滤已软删笔记本 => 不存在按 404
		Notebook nb = notebookService.getById(notebookId);
		if (nb == null) {
			throw new NotFoundException("笔记本不存在");
		}
		if (!agentNotebookService.hasLink(agentId, notebookId)) {
			throw new ForbiddenException("agent 未关联该笔记本");
		}
	}

	/**
	 * 批量回填 notebookName（联查 t_notebook，管理端列表/树共用）
	 *
	 * @param metas 索引行列表（有则直接复用，无则忽略）
	 */
	public void fillNotebookNames(List<NoteMeta> metas) {
		if (metas == null || metas.isEmpty()) {
			return;
		}
		List<Long> ids = metas.stream()
				.map(NoteMeta::getNotebookId)
				.filter(java.util.Objects::nonNull)
				.distinct().toList();
		if (ids.isEmpty()) {
			return;
		}
		Map<Long, String> nameById = notebookService.listByIds(ids).stream()
				.collect(Collectors.toMap(Notebook::getId, Notebook::getName, (a, b) -> a));
		metas.forEach(m -> m.setNotebookName(
				m.getNotebookId() == null ? null : nameById.get(m.getNotebookId())));
	}

	/**
	 * 管理端：索引分页（v3 迁自 /api/agent/admin/notes；agentId 过滤走关联表）
	 *
	 * @param page       页码（从 1 开始）
	 * @param size       每页条数
	 * @param agentId    关联 agent 过滤（可选；该 agent 无任何关联时返回空页）
	 * @param notebookId 笔记本过滤（v3.5 可选；D1 直接 WHERE notebook_id）
	 * @param keyword    标题 LIKE 搜索（可选）
	 * @return 分页结果（update_time 降序，notebookName 已回填）
	 */
	public IPage<NoteMeta> pageSearch(long page, long size, Long agentId, Long notebookId, String keyword) {
		LambdaQueryWrapper<NoteMeta> wrapper = Wrappers.lambdaQuery();
		// agentId 维度：先经关联表取 nid 集合，再 IN 过滤（空集合=无关联→空页）
		if (agentId != null) {
			List<String> nids = agentResourceService.listResourceIdsByAgent(agentId, AgentResource.TYPE_NOTE);
			if (nids.isEmpty()) {
				return new Page<NoteMeta>(page, size);
			}
			wrapper.in(NoteMeta::getNid, nids);
		}
		// v3.5：notebookId 过滤（D1 笔笔直挂以该 notebook）
		wrapper.eq(notebookId != null, NoteMeta::getNotebookId, notebookId)
				.like(keyword != null && !keyword.isBlank(), NoteMeta::getTitle, keyword)
				.orderByDesc(NoteMeta::getUpdateTime);
		IPage<NoteMeta> result = page(new Page<>(page, size), wrapper);
		fillNotebookNames(result.getRecords());
		return result;
	}

	/**
	 * 按 nid 物理删除索引行
	 *
	 * @param nid 对外引用键
	 * @return 是否删除到行（行不存在返回 false）
	 */
	public boolean deleteByNid(String nid) {
		return remove(Wrappers.<NoteMeta>lambdaQuery().eq(NoteMeta::getNid, nid));
	}

	/**
	 * 某笔记本下全部笔记（v3.5 新增，门户/管理端按 notebook 直接 WHERE）
	 *
	 * @param notebookId 笔记本 id
	 * @return 索引行列表（update_time 降序、同时间 id 降序）
	 */
	public List<NoteMeta> listByNotebook(Long notebookId) {
		if (notebookId == null) {
			return new ArrayList<>();
		}
		return listSorted(Wrappers.<NoteMeta>lambdaQuery().eq(NoteMeta::getNotebookId, notebookId));
	}

	// ============================================================
	// PATCH 挪动/重命名（M3.7 新增：按 nid 改 meta，保留 nid/文件/关联）
	// ============================================================

	/**
	 * 挪动/重命名笔记（M3.7，PATCH /api/agent/notes/{nid}）
	 * <p>
	 * 按 nid 直接改 meta（dirPath/title/notebookId），保留 nid 与文件、关联行不动
	 * （文件路径 = NOTES_ROOT/notes/{nid}.md 只跟 nid 走，与 dirPath 解耦，改 meta 无需迁移文件）。
	 * <p>
	 * 校验链（fail-closed，顺序执行）：
	 * <ol>
	 * <li>nid 存在且当前 agent 已关联（hasLink）→ 否则统一 404（防枚举，同 delete 语义）</li>
	 * <li>改 notebookId → 复用 {@link #validateNotebookWrite}（notebook 不存在/已软删
	 * 404、agent 未关联 403）</li>
	 * <li>dirPath 规范化 + 校验（复用
	 * {@link NoteStorageManager#normalizeDirPath}/{@link NoteStorageManager#validateDirPath}）</li>
	 * <li>title 规范化 + 校验（复用 {@link NoteStorageManager#prepareTitle}）</li>
	 * <li>冲突检测：改后元组 (notebookId, dirPath, title) 与同 notebook 下其他笔记（排除自身 nid）重复 →
	 * 422</li>
	 * <li>updateById 只更新请求中出现的字段，update_time 刷新；不动文件、不动 t_agent_resource 关联行</li>
	 * </ol>
	 *
	 * @param agentId    当前 agent id（X-API-Key 解析）
	 * @param nid        笔记对外引用键
	 * @param notebookId 目标笔记本 id（可空=不改）
	 * @param dirPath    目标目录（可空=不改；空串=根目录）
	 * @param title      目标标题（可空=不改）
	 * @return 更新后索引行
	 */
	public NoteMeta patchMeta(Long agentId, String nid, Long notebookId, String dirPath, String title) {
		// 1. nid 存在且当前 agent 已关联（hasLink）→ 否则统一 404（防枚举，同 delete 语义）
		NoteMeta meta = findByNid(nid);
		if (meta == null || !agentResourceService.hasLink(agentId, AgentResource.TYPE_NOTE, nid)) {
			throw new NotFoundException("笔记不存在");
		}
		// 至少一项非空（全空 → 400）
		if (notebookId == null && dirPath == null && title == null) {
			throw new BadRequestException("至少修改一项（notebookId/dirPath/title）");
		}
		// 2. 改 notebookId → 复用既有 D5 校验（不存在/软删 404、未关联 403）
		if (notebookId != null) {
			validateNotebookWrite(agentId, notebookId);
		}
		// 3. dirPath 规范化 + 校验（复用 upsert 同款）
		String effDir = dirPath != null ? noteStorageManager.normalizeDirPath(dirPath) : meta.getDirPath();
		if (dirPath != null) {
			noteStorageManager.validateDirPath(effDir);
		}
		// 4. title 规范化 + 校验（复用 upsert 同款）
		String effTitle = title != null ? noteStorageManager.prepareTitle(title) : meta.getTitle();
		// 5. 冲突检测：改后元组与同 notebook 下其他笔记（排除自身 nid）重复 → 422（防止 upsert 锚点歧义）
		Long effNotebookId = notebookId != null ? notebookId : meta.getNotebookId();
		if (conflictExists(effNotebookId, effDir, effTitle, nid)) {
			throw new UnprocessableEntityException("同笔记本下已存在同名同目录笔记");
		}
		// 6. 更新索引：只更新请求中出现的字段，update_time 刷新；不动文件、不动关联行
		NoteMeta update = new NoteMeta();
		update.setId(meta.getId());
		if (notebookId != null) {
			update.setNotebookId(effNotebookId);
		}
		if (dirPath != null) {
			update.setDirPath(effDir);
		}
		if (title != null) {
			update.setTitle(effTitle);
		}
		update.setUpdateTime(new Date());
		updateById(update);

		// KB 钩子：title/notebookId 变化影响检索索引（Lucene title 字段、chunk 冗余列），
		// 发 NoteSavedEvent 触发整篇重建分块（分块内容未变但重写成本低，语义简单）
		eventPublisher.publishEvent(
				new com.youlxb2008.admin.modules.note.kb.event.NoteSavedEvent(nid));

		// 回填内存实体供返回（避免再查库）
		meta.setNotebookId(effNotebookId);
		meta.setDirPath(effDir);
		meta.setTitle(effTitle);
		meta.setUpdateTime(update.getUpdateTime());
		return meta;
	}

	/**
	 * 冲突检测：同 notebook 下是否存在 (notebook_id, dir_path, title) 相同的其他笔记（排除自身）
	 * <p>
	 * 跨 notebook 同名不判冲突（upsert 锚点按 notebook 归属隔离，见 api.md §1.7）。
	 *
	 * @param notebookId 改后元组的笔记本 id
	 * @param dirPath    改后目录
	 * @param title      改后标题
	 * @param excludeNid 排除的自身 nid（可空）
	 * @return true=存在冲突
	 */
	public boolean conflictExists(Long notebookId, String dirPath, String title, String excludeNid) {
		if (notebookId == null || dirPath == null || title == null) {
			return false;
		}
		return count(Wrappers.<NoteMeta>lambdaQuery()
				.eq(NoteMeta::getNotebookId, notebookId)
				.eq(NoteMeta::getDirPath, dirPath)
				.eq(NoteMeta::getTitle, title)
				.ne(excludeNid != null, NoteMeta::getNid, excludeNid)) > 0;
	}

	// ============================================================
	// 目录树聚合（v3 新增：数据驱动，dir_path 内存建树，废弃扫盘）
	// ============================================================

	/**
	 * dir_path 内存建树（folder/note 节点）
	 * <p>
	 * folder 按路径段逐级创建（共享前缀复用同一节点）；note 挂到所属 folder；
	 * 根目录笔记（dir_path=""）直接挂根。排序：每层 folder 字典序在前、note 字典序在后。
	 *
	 * @param metas 索引行列表（来自关联过滤后的结果）
	 * @return 树节点列表
	 */
	public List<NoteTreeNode> buildTree(List<NoteMeta> metas) {
		// 虚拟根容器：children 同时容纳根目录笔记与一级 folder，最后返回其 children
		NoteTreeNode virtualRoot = new NoteTreeNode();
		virtualRoot.setChildren(new ArrayList<>());
		// 已创建 folder 的完整路径 → 节点（共享前缀复用，避免重复创建）
		Map<String, NoteTreeNode> folderByPath = new HashMap<>();
		for (NoteMeta meta : metas) {
			String dirPath = meta.getDirPath() == null ? "" : meta.getDirPath();
			NoteTreeNode parent = ensureFolder(dirPath, virtualRoot, folderByPath);
			NoteTreeNode note = new NoteTreeNode();
			note.setName(meta.getTitle());
			note.setType("note");
			note.setNid(meta.getNid());
			note.setNotebookId(meta.getNotebookId());
			note.setNotebookName(meta.getNotebookName());
			note.setUpdateTime(meta.getUpdateTime());
			parent.getChildren().add(note);
		}
		sortTree(virtualRoot.getChildren());
		return virtualRoot.getChildren();
	}

	/**
	 * 确保目录路径对应的 folder 节点存在（逐段创建，缺失即建）
	 *
	 * @param dirPath      目录路径（""=根，直接返回虚拟根）
	 * @param virtualRoot  虚拟根容器
	 * @param folderByPath 路径 → folder 节点缓存
	 * @return 该目录路径对应的 folder 节点（note 的挂载父节点）
	 */
	private NoteTreeNode ensureFolder(String dirPath, NoteTreeNode virtualRoot,
			Map<String, NoteTreeNode> folderByPath) {
		if (dirPath.isEmpty()) {
			return virtualRoot;
		}
		NoteTreeNode parent = virtualRoot;
		// 逐段累积路径 key：a/b → 先建 a 再建 a/b，保证父 folder 先于子 folder 存在
		StringBuilder prefix = new StringBuilder();
		for (String segment : dirPath.split("/")) {
			if (prefix.length() > 0) {
				prefix.append('/');
			}
			prefix.append(segment);
			String key = prefix.toString();
			NoteTreeNode node = folderByPath.get(key);
			if (node == null) {
				node = new NoteTreeNode();
				node.setName(segment);
				node.setType("folder");
				node.setChildren(new ArrayList<>());
				parent.getChildren().add(node);
				folderByPath.put(key, node);
			}
			parent = node;
		}
		return parent;
	}

	/**
	 * 树排序（递归）：每层 folder 字典序在前、note 字典序在后（与 v2 扫盘树排序一致）
	 */
	private void sortTree(List<NoteTreeNode> nodes) {
		List<NoteTreeNode> folders = new ArrayList<>();
		List<NoteTreeNode> notes = new ArrayList<>();
		for (NoteTreeNode node : nodes) {
			if ("folder".equals(node.getType())) {
				folders.add(node);
			} else {
				notes.add(node);
			}
		}
		folders.sort(Comparator.comparing(NoteTreeNode::getName,
				Comparator.nullsLast(Comparator.naturalOrder())));
		notes.sort(Comparator.comparing(NoteTreeNode::getName,
				Comparator.nullsLast(Comparator.naturalOrder())));
		folders.forEach(f -> sortTree(f.getChildren()));
		nodes.clear();
		nodes.addAll(folders);
		nodes.addAll(notes);
	}

	// ============================================================
	// 门户 v2.1 增补查询族（v3.5 改造：主维度 agent → notebook，按 notebook_id 直接过滤）
	// 实现说明：SQL 侧只做 notebook_id 过滤 + update_time/id 排序；关键字命中、tag 精确
	// 匹配、聚合、limit 钳制在 Java 内存等价实现（语义与 SQL LIKE / FIND_IN_SET 一致）。
	// ============================================================

	/**
	 * 标题搜索（契约 api.md §3 v4.0，GET /api/portal/notes/search）
	 * <p>
	 * title 包含关键字（忽略大小写）；notebookId 必填（D1 直接 WHERE notebook_id）；
	 * limit 钳制 ≤50。仅搜索引，不扫文件内容。
	 */
	public List<NoteMeta> searchByKeyword(Long notebookId, String keyword, int limit) {
		String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
		return listVisible(notebookId).stream()
				.filter(m -> m.getTitle() != null
						&& m.getTitle().toLowerCase(Locale.ROOT).contains(kw))
				.limit(clampLimit(limit))
				.collect(Collectors.toList());
	}

	/**
	 * tags 聚合（契约 api.md §3 v4.0，GET /api/portal/notes/tags）
	 * <p>
	 * 逗号分隔展开，空白/单条内 trim、去空；每篇笔记内先按 tag 去重（同一 tag 在一篇
	 * 笔记里只计 1 次，等价「打该 tag 的笔记数」），再跨笔记累加；count 降序再 tag 字典序。
	 */
	public List<TagCountVO> aggregateTags(Long notebookId) {
		Map<String, Long> counts = new HashMap<>();
		for (NoteMeta m : listVisible(notebookId)) {
			if (m.getTags() == null || m.getTags().isBlank()) {
				continue;
			}
			Set<String> seen = new HashSet<>();
			for (String raw : m.getTags().split(",")) {
				String tag = raw.trim();
				if (!tag.isEmpty() && seen.add(tag)) {
					counts.merge(tag, 1L, Long::sum);
				}
			}
		}
		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed()
						.thenComparing(Map.Entry::getKey))
				.map(e -> new TagCountVO(e.getKey(), e.getValue()))
				.collect(Collectors.toList());
	}

	/**
	 * 按 tag 精确筛选（契约 api.md §3 v4.0，GET /api/portal/notes/by-tag）
	 * <p>
	 * 精确匹配（等价 MySQL FIND_IN_SET，避免 %tag% 误匹配，如 ai ≠ aigc）；
	 * notebookId 语义同 {@link #searchByKeyword}；limit 钳制 ≤50。
	 */
	public List<NoteMeta> listByTag(Long notebookId, String tag, int limit) {
		if (tag == null || tag.isBlank()) {
			throw new BadRequestException("tag 不能为空");
		}
		String target = tag.trim();
		return listVisible(notebookId).stream()
				.filter(m -> matchesTag(m.getTags(), target))
				.limit(clampLimit(limit))
				.collect(Collectors.toList());
	}

	/**
	 * 最近更新（契约 api.md §3 v4.0，GET /api/portal/notes/recent）
	 * <p>
	 * update_time 降序、同时间按 id 降序；notebookId 语义同上；limit 钳制 ≤50。
	 */
	public List<NoteMeta> listRecent(Long notebookId, int limit) {
		return listVisible(notebookId).stream()
				.limit(clampLimit(limit))
				.collect(Collectors.toList());
	}

	// ------------------------------------------------------------
	// 内部：可见域过滤 / 排序 / 钳制 / tag 精确匹配
	// ------------------------------------------------------------

	/**
	 * 拉取可见笔记：notebookId 必填（D1），WHERE notebook_id=notebookId，
	 * 统一按 update_time 降序、同时间 id 降序排序（recent 语义）。
	 */
	private List<NoteMeta> listVisible(Long notebookId) {
		if (notebookId == null) {
			return new ArrayList<>();
		}
		// 必须排序：recent/search/by-tag 契约均为 update_time 降序、同时间 id 降序；
		// 此前漏调 listSorted 导致按主键升序返回，「最近更新」列表变成旧→新
		return listSorted(Wrappers.<NoteMeta>lambdaQuery().eq(NoteMeta::getNotebookId, notebookId));
	}

	/** 按 wrapper 查询并按 update_time 降序、同时间 id 降序排序 */
	private List<NoteMeta> listSorted(LambdaQueryWrapper<NoteMeta> wrapper) {
		List<NoteMeta> all = new ArrayList<>(list(wrapper));
		all.sort(Comparator.comparing(NoteMeta::getUpdateTime,
				Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(NoteMeta::getId, Comparator.reverseOrder()));
		return all;
	}

	/** limit 钳制到 [1, 50]（契约：最大 50） */
	private int clampLimit(int limit) {
		if (limit <= 0) {
			return 1;
		}
		return Math.min(limit, MAX_RESULT_LIMIT);
	}

	/** tags 逗号分隔精确匹配（等价 FIND_IN_SET，区分边界避免 ai 误中 aigc） */
	private boolean matchesTag(String tagsCsv, String tag) {
		if (tagsCsv == null || tagsCsv.isBlank()) {
			return false;
		}
		for (String raw : tagsCsv.split(",")) {
			if (raw.trim().equals(tag)) {
				return true;
			}
		}
		return false;
	}
}
