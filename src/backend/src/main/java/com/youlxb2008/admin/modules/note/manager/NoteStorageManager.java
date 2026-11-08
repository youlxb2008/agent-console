package com.youlxb2008.admin.modules.note.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Date;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.note.vo.NoteContentVO;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 笔记文件存储 Manager（v3：由 agent 域 NoteStorageService 迁入 note 域并改名）
 * <p>
 * v3 扁平存储契约（设计 §1.3）：一笔记一文件 {@code NOTES_ROOT/notes/{nid}.md}，
 * 路径由 nid 派生不再入库；🔥 移除 agent-{id}/ 前缀拼接、扫盘 tree、path 兜底读。
 * <ul>
 *   <li>写侧：黑名单/长度/层深/2MB 校验 → 智能覆写锚点 (dir_path, title) + hasLink → 原子写</li>
 *   <li>索引：t_note_meta upsert（覆写保留原 nid；索引失败不回滚文件，记审计 error）</li>
 *   <li>关联：新建笔记写创建者关联行（t_agent_resource）</li>
 *   <li>读侧：nid 格式强校验 + resolve 后必须落在 NOTES_ROOT/notes/ 内（fail-closed）</li>
 * </ul>
 * 一致性次序（设计 §3.1）：先文件（真相源）→ 后索引 → 后关联；后两步失败不回滚文件。
 */
@Slf4j
@Component
public class NoteStorageManager {

	/** 路径黑名单字符（沿用 v2 契约 data-model.md §5.2） */
	private static final String BLACKLIST_CHARS = "/\\:*?\"<>|";

	private static final String MARKDOWN_EXT = ".md";
	/** 固定一级目录：NOTES_ROOT/notes/（与历史 agent-{id}/ 迁移残留区隔） */
	private static final String NOTES_DIR = "notes";
	private static final String NID_PREFIX = "n_";
	private static final int NID_RANDOM_LEN = 10;
	private static final int NID_RETRY = 3;
	private static final Pattern NID_PATTERN = Pattern.compile("^n_[0-9A-Za-z]{10}$");
	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** title ≤200 字符（=文件名去 .md） */
	private static final int MAX_TITLE_LEN = 200;
	/** 单目录段 ≤64 字符 */
	private static final int MAX_SEGMENT_LEN = 64;
	/** dirPath 层深 ≤10 */
	private static final int MAX_DIR_DEPTH = 10;
	/** contentMd ≤2MB */
	private static final long MAX_CONTENT_BYTES = 2L * 1024 * 1024;

	@Value("${notes.root:./data/notes}")
	private String notesRoot;

	@Autowired
	private NoteMetaService noteMetaService;

	@Autowired
	private AgentResourceService agentResourceService;

	/** KB 索引钩子：索引落库成功后发事件，由 KnowledgeIndexService 异步建分块/向量/Lucene 索引 */
	@Autowired
	private org.springframework.context.ApplicationEventPublisher eventPublisher;

	/**
	 * 启动即建扁平目录 NOTES_ROOT/notes/（设计 §4.2：应用启动时自动创建）
	 */
	@PostConstruct
	public void initNotesDir() {
		try {
			Files.createDirectories(notesDir());
		} catch (IOException e) {
			// 目录创建失败不阻断启动：首次写笔记时 atomicWrite 会再次 createDirectories 并抛出可读异常
			log.warn("NOTES_ROOT/notes 目录初始化失败（首次写入时重试）: {}", e.getMessage());
		}
	}

	// ============================================================
	// upsert（智能覆写，设计 §3.1）
	// ============================================================

	/**
	 * 写/覆写笔记（智能覆写语义，v3）
	 * <p>
	 * 锚点 (dir_path, title) + 关联校验：
	 * <ol>
	 *   <li>锚点未找到 = 新建（生成新 nid + 创建者关联）</li>
	 *   <li>找到且当前 agent 已关联 = 覆写（保留原 nid，更新内容/tags）</li>
	 *   <li>找到但当前 agent 未关联 = 新建（不覆写他人笔记）</li>
	 * </ol>
	 * 一致性次序：先文件 → 后索引 → 后关联；索引/关联失败不回滚文件（记审计 error）。
	 *
	 * @param agentId   写入 agent（t_agent.id，新建时写创建者关联）
	 * @param agentName 写入 agent 名（关联行 create_by）
	 * @param notebookId 所属笔记本 id（v3.5 D1：笔记直接挂 notebook）
	 * @param dirPath   目录（可为空串=根目录）
	 * @param rawTitle  标题（自动去尾部 .md 差异）
	 * @param contentMd Markdown 正文
	 * @param tags      逗号分隔标签（可选）
	 * @return 结果（nid/dirPath/title/updateTime/created/indexOk/linkOk）
	 */
	public UpsertResult upsert(Long agentId, String agentName, Long notebookId, String dirPath,
			String rawTitle, String contentMd, String tags) {
		String title = prepareTitle(rawTitle);
		String dir = normalizeDirPath(dirPath);
		validateDirPath(dir);
		validateContentSize(contentMd);

		// 智能覆写判定：同锚点存在 且 当前 agent 已关联 → 覆写
		NoteMeta existing = noteMetaService.findByDirAndTitle(dir, title);
		boolean overwrite = existing != null
				&& agentResourceService.hasLink(agentId, AgentResource.TYPE_NOTE, existing.getNid());
		String nid = overwrite ? existing.getNid() : generateUniqueNid();
		Date now = new Date();

		// 第 1 步：原子写文件（真相源；成功即对关联方可见）
		Path target = resolveNoteFile(nid);
		atomicWrite(target, contentMd);

		// 第 2 步：索引 upsert（失败不回滚文件，返回 indexOk=false 供审计 error）
		boolean indexOk;
		if (overwrite) {
			// 覆写：锚点即定位（dir/title 不变），刷新 tags/update_time；notebook_id 更新为目标新值（移动语义，调用方已完成 D5 校验）
			existing.setNotebookId(notebookId);
			existing.setTags(tags);
			existing.setUpdateTime(now);
			indexOk = tryUpdateIndex(existing);
		} else {
			// 新建：uk_nid 冲突重试（理论极小概率）
			NoteMeta meta = new NoteMeta();
			meta.setNid(nid);
			meta.setNotebookId(notebookId);
			meta.setDirPath(dir);
			meta.setTitle(title);
			meta.setTags(tags);
			meta.setCreateTime(now);
			meta.setUpdateTime(now);
			indexOk = tryInsertIndex(meta);
			if (!indexOk) {
				return new UpsertResult(null, dir, title, now, true, false, false);
			}
			// 第 3 步：创建者关联（失败不回滚，返回 linkOk=false 供审计 error——此时笔记存在但
			// 不出现在任何笔记本，管理端可人工补挂关联修复）
			boolean linkOk = tryLink(agentId, nid, agentName);
			// KB 钩子：索引成功即发事件（异步分块/向量化，失败不影响笔记）
			eventPublisher.publishEvent(new com.youlxb2008.admin.modules.note.kb.event.NoteSavedEvent(nid));
			return new UpsertResult(nid, dir, title, now, true, true, linkOk);
		}
		if (!indexOk) {
			return new UpsertResult(nid, dir, title, now, false, false, true);
		}
		// KB 钩子：覆写成功（indexOk=true）同样发事件，索引服务整篇替换分块/向量
		eventPublisher.publishEvent(new com.youlxb2008.admin.modules.note.kb.event.NoteSavedEvent(nid));
		return new UpsertResult(nid, dir, title, now, false, true, true);
	}

	// ============================================================
	// delete（物理删三步：文件 → 索引 → 全部关联行）
	// ============================================================

	/**
	 * 彻底删除笔记（v3：文件 + 索引 + 全部关联行）
	 * <p>
	 * 调用方（agent 域/管理端 Controller）已完成归属校验（hasLink / 权限码）。
	 * 文件成功、索引失败 → 抛业务异常（保留孤儿文件不可见）；
	 * 关联行删除失败 → 仅告警（索引已删，树/查询不再出现，悬挂关联行无副作用）。
	 *
	 * @param nid 对外引用键
	 */
	public void deleteFully(String nid) {
		NoteMeta meta = noteMetaService.findByNid(nid);
		if (meta == null) {
			throw new NotFoundException("笔记不存在");
		}
		// 第 1 步：删文件（真相源）
		try {
			Files.deleteIfExists(resolveNoteFile(nid));
		} catch (IOException e) {
			throw new BusinessException("笔记文件删除失败", e);
		}
		// 第 2 步：删索引行（失败抛异常，文件已成孤儿不可见）
		try {
			noteMetaService.deleteByNid(nid);
		} catch (Exception e) {
			log.error("笔记索引删除失败（文件已删除，保留孤儿索引行）: {}", e.getMessage());
			throw new BusinessException("笔记索引删除失败");
		}
		// 第 3 步：级联清全部关联行（失败仅告警：悬挂关联行无读侧副作用）
		try {
			agentResourceService.deleteByResource(AgentResource.TYPE_NOTE, nid);
		} catch (Exception e) {
			log.error("笔记关联行级联删除失败（悬挂行，无读侧副作用）: {}", e.getMessage());
		}
		// KB 钩子：删除完成后发事件（异步清 t_note_chunk 分块 + Lucene 文档）
		eventPublisher.publishEvent(new com.youlxb2008.admin.modules.note.kb.event.NoteDeletedEvent(nid));
	}

	// ============================================================
	// read（nid 唯一定位，fail-closed）
	// ============================================================

	/**
	 * 按 nid 读笔记内容并组装 VO（索引 + 文件 + 贡献者）
	 * <p>
	 * 索引行不存在或文件缺失（孤儿索引行）均按 404 处理（设计 §1.3）；
	 * contributors（全部关联 agent）在此统一填充，门户/管理端共用。
	 *
	 * @param nid 对外引用键（格式强校验 ^n_[0-9A-Za-z]{10}$，防路径注入）
	 * @return 内容 VO
	 */
	public NoteContentVO readContent(String nid) {
		NoteMeta meta = noteMetaService.findByNid(nid);
		if (meta == null) {
			throw new NotFoundException("笔记不存在");
		}
		String contentMd = readRaw(nid);
		NoteContentVO vo = new NoteContentVO();
		vo.setNid(meta.getNid());
		vo.setTitle(meta.getTitle());
		vo.setDirPath(meta.getDirPath());
		vo.setContentMd(contentMd);
		vo.setUpdateTime(meta.getUpdateTime());
		vo.setSizeBytes((long) contentMd.getBytes(StandardCharsets.UTF_8).length);
		vo.setContributors(agentResourceService.listNoteContributors(nid));
		return vo;
	}

	/**
	 * 读笔记原文（nid 格式校验 + fail-closed resolve）
	 *
	 * @param nid 对外引用键
	 * @return Markdown 原文；文件不存在 → 404（孤儿索引行）
	 */
	public String readRaw(String nid) {
		Path target = resolveNoteFile(nid);
		if (!Files.isRegularFile(target)) {
			throw new NotFoundException("笔记不存在");
		}
		try {
			return Files.readString(target, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new NotFoundException("笔记不存在");
		}
	}

	// ============================================================
	// 内部：校验 / 路径 / 文件 IO / nid 生成
	// ============================================================

	/** 标题校验 + 去 .md 后缀差异，返回规范标题（=文件名去 .md）。供 upsert 与 PATCH 重命名（M3.7）复用 */
	public String prepareTitle(String rawTitle) {
		if (rawTitle == null) {
			throw new BadRequestException("标题不能为空");
		}
		String title = stripMarkdownExt(rawTitle);
		if (title.isEmpty()) {
			throw new BadRequestException("标题不能为空");
		}
		if (title.length() > MAX_TITLE_LEN) {
			throw new BadRequestException("标题长度不能超过 " + MAX_TITLE_LEN);
		}
		if (!title.equals(title.trim())) {
			throw new BadRequestException("标题不能以空格开头或结尾");
		}
		if (title.startsWith(".")) {
			throw new BadRequestException("标题不能以 . 开头");
		}
		validateNoBlacklist(title, "标题");
		return title;
	}

	/** dirPath 规范化：null → 空串（根目录）。供 upsert 与 PATCH 挪动（M3.7）复用 */
	public String normalizeDirPath(String dirPath) {
		return dirPath == null ? "" : dirPath;
	}

	/** dirPath 校验（空串=根目录；'/'-分隔逐段校验）。供 upsert 与 PATCH 挪动（M3.7）复用 */
	public void validateDirPath(String dirPath) {
		if (dirPath == null || dirPath.isBlank()) {
			return;
		}
		if (!dirPath.equals(dirPath.trim())) {
			throw new BadRequestException("目录不能以空格开头或结尾");
		}
		if (dirPath.startsWith(".")) {
			throw new BadRequestException("目录不能以 . 开头");
		}
		String[] segments = dirPath.split("/");
		if (segments.length > MAX_DIR_DEPTH) {
			throw new BadRequestException("目录层级不能超过 " + MAX_DIR_DEPTH);
		}
		for (String seg : segments) {
			if (seg.isEmpty()) {
				throw new BadRequestException("目录存在空段");
			}
			if (seg.equals(".") || seg.equals("..")) {
				throw new BadRequestException("目录不允许 . 或 .. 段");
			}
			if (seg.length() > MAX_SEGMENT_LEN) {
				throw new BadRequestException("目录单段长度不能超过 " + MAX_SEGMENT_LEN);
			}
			if (!seg.equals(seg.trim())) {
				throw new BadRequestException("目录段不能以空格开头或结尾");
			}
			validateNoBlacklist(seg, "目录段");
		}
	}

	/** contentMd ≤2MB（按 UTF-8 字节数） */
	private void validateContentSize(String contentMd) {
		if (contentMd == null) {
			return;
		}
		long bytes = contentMd.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_CONTENT_BYTES) {
			throw new BadRequestException("正文不能超过 2MB");
		}
	}

	private void validateNoBlacklist(String value, String field) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (BLACKLIST_CHARS.indexOf(c) >= 0) {
				throw new BadRequestException(field + "包含非法字符：" + c);
			}
			if (Character.isISOControl(c)) {
				throw new BadRequestException(field + "包含控制字符");
			}
		}
	}

	private String stripMarkdownExt(String title) {
		if (title.toLowerCase(java.util.Locale.ROOT).endsWith(MARKDOWN_EXT)) {
			return title.substring(0, title.length() - MARKDOWN_EXT.length());
		}
		return title;
	}

	/** NOTES_ROOT 绝对规范路径 */
	private Path rootDir() {
		return Paths.get(notesRoot).toAbsolutePath().normalize();
	}

	/** 扁平存储目录 NOTES_ROOT/notes/ */
	private Path notesDir() {
		return rootDir().resolve(NOTES_DIR);
	}

	/**
	 * nid → 文件路径（fail-closed：格式强校验 + resolve/normalize 后必须仍在 notes/ 内）
	 * <p>
	 * 双重防线：即使 nid 格式校验被绕过，normalize 越界（如 ../）也按不存在处理。
	 */
	private Path resolveNoteFile(String nid) {
		if (nid == null || !NID_PATTERN.matcher(nid).matches()) {
			throw new NotFoundException("笔记不存在");
		}
		Path base = notesDir();
		Path target = base.resolve(nid + MARKDOWN_EXT).normalize();
		if (!target.startsWith(base)) {
			throw new NotFoundException("笔记不存在");
		}
		return target;
	}

	/** 原子写：临时文件 + move(ATOMIC_MOVE, REPLACE_EXISTING)，避免半写被读到 */
	private void atomicWrite(Path target, String contentMd) {
		try {
			Files.createDirectories(target.getParent());
			Path tmp = Files.createTempFile(target.getParent(), ".agc-", ".tmp");
			// createTempFile 默认 600，显式 644 保证宿主运维账号直读（挂载盘运维/备份）。
			// Windows 不支持 POSIX 权限视图（setPosixFilePermissions 抛 UnsupportedOperationException），
			// 仅在文件系统声明支持 posix 视图（Linux/macOS）时才设置。
			if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
			}
			try {
				byte[] bytes = contentMd == null ? new byte[0] : contentMd.getBytes(StandardCharsets.UTF_8);
				Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			throw new BusinessException("笔记文件写入失败", e);
		}
	}

	/** 索引 update（覆写路径）；失败记日志返回 false（文件已写入，保留孤儿语义由调用方审计） */
	private boolean tryUpdateIndex(NoteMeta meta) {
		try {
			noteMetaService.updateById(meta);
			return true;
		} catch (Exception e) {
			log.error("笔记索引更新失败（文件已写入，索引未刷新）: {}", e.getMessage());
			return false;
		}
	}

	/** 索引 insert（新建路径）；uk_nid 冲突重试，最终失败返回 false（孤儿文件） */
	private boolean tryInsertIndex(NoteMeta meta) {
		for (int i = 0; i < NID_RETRY; i++) {
			try {
				noteMetaService.save(meta);
				return true;
			} catch (DuplicateKeyException e) {
				// uk_nid 冲突（nid 随机碰撞，概率 62^10 分之一）：换 nid 重试
				log.warn("nid 冲突，重试 {}/{}", i + 1, NID_RETRY);
				meta.setNid(generateUniqueNid());
			} catch (Exception e) {
				log.error("笔记索引写入失败（文件已写入，保留孤儿文件）: {}", e.getMessage());
				return false;
			}
		}
		log.error("nid 生成连续冲突 {} 次，索引未写入（孤儿文件）", NID_RETRY);
		return false;
	}

	/** 创建者关联写入（失败不回滚，返回 false 供审计 error） */
	private boolean tryLink(Long agentId, String nid, String createBy) {
		try {
			agentResourceService.link(agentId, AgentResource.TYPE_NOTE, nid, createBy);
			return true;
		} catch (Exception e) {
			log.error("创建者关联写入失败（笔记存在但不在任何笔记本，管理端可补挂）: {}", e.getMessage());
			return false;
		}
	}

	/** 生成未占用的 nid（查索引去重；理论碰撞概率 62^10 分之一，仍兜底循环） */
	private String generateUniqueNid() {
		for (int i = 0; i < NID_RETRY; i++) {
			String nid = generateNid();
			if (noteMetaService.findByNid(nid) == null) {
				return nid;
			}
			log.warn("nid 已被占用，重新生成 {}/{}", i + 1, NID_RETRY);
		}
		// 连续占用仍给出可用性最好的结果：直接抛错由全局异常处理兜底
		throw new BusinessException("nid 生成冲突，请重试");
	}

	private String generateNid() {
		StringBuilder sb = new StringBuilder(NID_PREFIX);
		for (int i = 0; i < NID_RANDOM_LEN; i++) {
			sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
		}
		return sb.toString();
	}

	/**
	 * upsert 结果（v3）
	 */
	@Data
	public static class UpsertResult {

		/** 对外引用键；新建且索引写失败（孤儿文件）时为 null */
		private final String nid;

		/** 目录路径（根目录为 ""） */
		private final String dirPath;

		/** 笔记标题 */
		private final String title;

		/** 本次写入时间 */
		private final Date updateTime;

		/** true=新建；false=覆写（nid 不变） */
		private final boolean created;

		/** 索引是否写入成功；false 时调用方应记审计 error（响应仍成功） */
		private final boolean indexOk;

		/** 创建者关联是否写入成功（仅新建时有意义） */
		private final boolean linkOk;

		public UpsertResult(String nid, String dirPath, String title, Date updateTime,
				boolean created, boolean indexOk, boolean linkOk) {
			this.nid = nid;
			this.dirPath = dirPath;
			this.title = title;
			this.updateTime = updateTime;
			this.created = created;
			this.indexOk = indexOk;
			this.linkOk = linkOk;
		}
	}
}
