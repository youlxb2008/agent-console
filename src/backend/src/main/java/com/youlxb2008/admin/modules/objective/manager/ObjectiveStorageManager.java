package com.youlxb2008.admin.modules.objective.manager;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.objective.service.ObjectiveService;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 目标详情文件存储 Manager（M4.1，参照 NoteStorageManager 模式）
 * <p>
 * 一目标一文件 {@code OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md}（agent 私域目录隔离），
 * 文件路径 = root.resolve(bodyPath)（body_path 入库为相对路径 {@code agent-{id}/objective-{oid}.md}）。
 * <ul>
 *   <li>写侧：oid 格式强校验 + resolve 后必须落在 OBJECTIVES_ROOT 内（fail-closed）+ 原子写（临时文件 + move）+ 显式 644</li>
 *   <li>模板：`## 描述` + `## 子项`（空）+ `## 反思`；子项 = markdown checkbox（`- [x]`/`- [ ]`），agent 直接改文件</li>
 *   <li>进度：calcProgress 读 md 统计 checkbox → done/total*100（total=0 → 0），写接口时由 service 刷新到索引</li>
 *   <li>描述覆写：patch 提供 descMd 时替换 `## 描述` 段正文，子项/反思段保留</li>
 * </ul>
 */
@Slf4j
@Component
public class ObjectiveStorageManager {

	/** 对外引用键前缀 o_ + 10 位 base62 */
	private static final String OID_PREFIX = "o_";
	private static final int OID_RANDOM_LEN = 10;
	private static final int OID_RETRY = 3;
	private static final Pattern OID_PATTERN = Pattern.compile("^o_[0-9A-Za-z]{10}$");
	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** title ≤200 字符 */
	private static final int MAX_TITLE_LEN = 200;
	/** 详情 md 整体 ≤2MB */
	private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

	/** checkbox 行：`- [x]` / `- [X]`（done）/ `- [ ]`（todo） */
	private static final Pattern CHECKBOX_LINE = Pattern.compile("^\\s*-\\s*\\[([ xX])\\]\\s+(.*)$",
			Pattern.MULTILINE);
	/** 模板保留段标题行（独立成行）：`## 子项` / `## 反思`——descMd 中禁用（PATCH 400），同时是文件解析的段边界 */
	private static final Pattern RESERVED_SECTION = Pattern.compile("(?m)^##\\s*(?:子项|反思)\\s*$");
	/**
	 * 描述段正文：`## 描述` 标题后到下一个模板保留段（`## 子项` / `## 反思`）或文件结尾之间的内容。
	 * <p>
	 * bug 修复（2026-09-14）：原 lookahead `\n##\s` 匹配任意二级标题——descMd 本身含 `##` 标题时
	 * lazy 匹配提前截断，旧内容被当作 rest 保留，每次 PATCH 净增内容（目标 md 膨胀、新旧段落并存）。
	 * 现边界只认模板固定段；descMd 内任意其他 `##` 标题（含重复的 `## 描述`）都算描述正文，
	 * 重复段随覆写自然清除（损坏文件一次 PATCH 自愈）。
	 */
	private static final Pattern DESC_SECTION = Pattern.compile("(?s)^##\\s*描述\\s*\\n(.*?)(?=\\n##\\s*(?:子项|反思)\\s*(?:\\n|\\z)|\\z)");

	@Value("${objectives.root:./data/objectives}")
	private String objectivesRoot;

	/** oid 去重（findByOid 空占位；@Lazy 打破与 ObjectiveService 的循环依赖，运行时方法调用互依） */
	@org.springframework.context.annotation.Lazy
	@org.springframework.beans.factory.annotation.Autowired
	private ObjectiveService objectiveService;

	/**
	 * 启动即建根目录 OBJECTIVES_ROOT/（首次写文件时 atomicWrite 会再 createDirectories 幂等兜底）
	 */
	@PostConstruct
	public void initObjectivesRoot() {
		try {
			Files.createDirectories(rootDir());
		} catch (IOException e) {
			log.warn("OBJECTIVES_ROOT 目录初始化失败（首次写入时重试）: {}", e.getMessage());
		}
	}

	// ============================================================
	// create：生成 oid → 写模板 md → 返回 oid/bodyPath
	// ============================================================

	/**
	 * 生成详情 md 并落盘（模板：描述 + 空子项 + 反思）
	 *
	 * @param agentId 归属 agent（目录 agent-{id}）
	 * @param oid     对外引用键（已生成未占用）
	 * @param descMd  描述段 markdown（可空）
	 * @return body_path 相对路径 {@code agent-{id}/objective-{oid}.md}
	 */
	public String writeNewBody(Long agentId, String oid, String descMd) {
		validateBodySize(descMd);
		String body = buildTemplate(descMd);
		atomicWrite(resolveObjectiveFile(agentId, oid), body);
		return relativePath(agentId, oid);
	}

	/**
	 * 详情 md 模板：`## 描述` + `## 子项`（空模板，agent 直接追加 checkbox）+ `## 反思`
	 */
	public String buildTemplate(String descMd) {
		String desc = descMd == null ? "" : descMd;
		StringBuilder sb = new StringBuilder();
		sb.append("## 描述\n").append(desc);
		if (!desc.isEmpty() && !desc.endsWith("\n")) {
			sb.append('\n');
		}
		sb.append("\n## 子项\n\n## 反思\n");
		return sb.toString();
	}

	// ============================================================
	// patch：描述段覆写（保留子项/反思）+ 读原文
	// ============================================================

	/**
	 * 覆写描述段：把当前 md 的 `## 描述` 段正文替换为 newDescMd，子项/反思段原样保留。
	 *
	 * @param bodyPath body_path 相对路径
	 * @param newDescMd 新描述段 markdown
	 * @return 覆写后的全文
	 */
	public String replaceDesc(String bodyPath, String newDescMd) {
		validateBodySize(newDescMd);
		// 保留段标题防御：descMd 含独立成行的 `## 子项` / `## 反思` 会破坏模板结构，明确拒绝（fail-fast）
		if (newDescMd != null && RESERVED_SECTION.matcher(newDescMd).find()) {
			throw new BadRequestException("descMd 不能包含模板保留段标题行（## 子项 / ## 反思），请改用三级标题或改写");
		}
		String current = readRaw(bodyPath);
		String desc = newDescMd == null ? "" : newDescMd;
		Matcher m = DESC_SECTION.matcher(current);
		StringBuilder replaced = new StringBuilder();
		if (m.find()) {
			// group(1) = 「描述」段正文（lazy 到模板保留段 `## 子项`/`## 反思` 或文件结尾）；rest 保留子项/反思段
			String rest = current.substring(m.end(1));
			replaced.append("## 描述\n").append(desc);
			if (!desc.isEmpty() && !desc.endsWith("\n")) {
				replaced.append('\n');
			}
			replaced.append(rest);
		} else {
			// 文件结构异常（无「描述」标题）：整体重建模板（子项丢失仅发生在极端手改场景，进度按新内容重算）
			log.warn("目标详情 md 缺少「## 描述」段，重建模板: {}", bodyPath);
			replaced.append(buildTemplate(newDescMd));
		}
		String out = replaced.toString();
		atomicWrite(resolveByBodyPath(bodyPath), out);
		return out;
	}

	// ============================================================
	// read：按 body_path 读原文（fail-closed）
	// ============================================================

	/**
	 * 读目标详情原文
	 *
	 * @param bodyPath body_path 相对路径（agent-{id}/objective-{oid}.md）
	 * @return Markdown 原文；文件缺失 → 404（孤儿索引行）
	 */
	public String readRaw(String bodyPath) {
		Path target = resolveByBodyPath(bodyPath);
		if (!Files.isRegularFile(target)) {
			throw new NotFoundException("目标详情不存在");
		}
		try {
			return Files.readString(target, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new NotFoundException("目标详情不存在");
		}
	}

	// ============================================================
	// 子项解析 + 进度计算
	// ============================================================

	/**
	 * 从 md 解析 checkbox 子项（`- [x]`/`- [ ]`）
	 *
	 * @param contentMd 详情 md 全文
	 * @return 子项列表（done/total/进度由调用方聚合）；无 checkbox 返回空列表
	 */
	public List<SubItem> parseSubItems(String contentMd) {
		List<SubItem> items = new ArrayList<>();
		if (contentMd == null) {
			return items;
		}
		Matcher m = CHECKBOX_LINE.matcher(contentMd);
		while (m.find()) {
			boolean done = "x".equalsIgnoreCase(m.group(1));
			items.add(new SubItem(done, m.group(2).trim()));
		}
		return items;
	}

	/**
	 * 计算进度 = done/total*100（保留 2 位小数）；total=0 → 0
	 *
	 * @param contentMd 详情 md 全文
	 * @return 0-100 的进度值
	 */
	public BigDecimal calcProgress(String contentMd) {
		List<SubItem> items = parseSubItems(contentMd);
		int total = items.size();
		if (total == 0) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		int done = 0;
		for (SubItem it : items) {
			if (it.isDone()) {
				done++;
			}
		}
		return BigDecimal.valueOf(done * 100.0d / total).setScale(2, RoundingMode.HALF_UP);
	}

	// ============================================================
	// oid 生成 / 校验
	// ============================================================

	/** 生成未被占用的 oid（查索引去重；理论碰撞概率 62^10 分之一，仍兜底循环） */
	public String generateUniqueOid() {
		for (int i = 0; i < OID_RETRY; i++) {
			String oid = generateOid();
			if (objectiveService.findByOid(oid) == null) {
				return oid;
			}
			log.warn("oid 已被占用，重新生成 {}/{}", i + 1, OID_RETRY);
		}
		throw new BusinessException("oid 生成冲突，请重试");
	}

	private String generateOid() {
		StringBuilder sb = new StringBuilder(OID_PREFIX);
		for (int i = 0; i < OID_RANDOM_LEN; i++) {
			sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
		}
		return sb.toString();
	}

	/** oid 格式强校验（^o_[0-9A-Za-z]{10}$，防路径注入/防枚举）；不合法返回 false（调用方按 404 处理） */
	public boolean isOidValid(String oid) {
		return oid != null && OID_PATTERN.matcher(oid).matches();
	}

	// ============================================================
	// 内部：路径 / 文件 IO / 校验
	// ============================================================

	/** OBJECTIVES_ROOT 绝对规范路径 */
	private Path rootDir() {
		return Paths.get(objectivesRoot).toAbsolutePath().normalize();
	}

	/** body_path 相对路径：agent-{id}/objective-{oid}.md */
	private String relativePath(Long agentId, String oid) {
		return "agent-" + agentId + "/objective-" + oid + ".md";
	}

	/**
	 * (agentId, oid) → 文件路径（fail-closed：oid 格式强校验 + normalize 后必须仍在 root 内）
	 */
	private Path resolveObjectiveFile(Long agentId, String oid) {
		if (agentId == null || !isOidValid(oid)) {
			throw new NotFoundException("目标不存在");
		}
		Path base = rootDir();
		Path target = base.resolve("agent-" + agentId).resolve("objective-" + oid + ".md").normalize();
		if (!target.startsWith(base)) {
			throw new NotFoundException("目标不存在");
		}
		return target;
	}

	/**
	 * body_path → 文件路径（fail-closed：目录段仅允许数字 id + objective-{oid}.md，normalize 越界按不存在处理）
	 */
	private Path resolveByBodyPath(String bodyPath) {
		if (bodyPath == null || bodyPath.isBlank()) {
			throw new NotFoundException("目标详情不存在");
		}
		Path base = rootDir();
		Path target = base.resolve(bodyPath).normalize();
		if (!target.startsWith(base)) {
			throw new NotFoundException("目标详情不存在");
		}
		return target;
	}

	/** 原子写：临时文件 + move(ATOMIC_MOVE, REPLACE_EXISTING)，避免半写被读到 */
	private void atomicWrite(Path target, String contentMd) {
		try {
			Files.createDirectories(target.getParent());
			Path tmp = Files.createTempFile(target.getParent(), ".agc-", ".tmp");
			// createTempFile 默认 600，显式 644 保证宿主运维账号直读（挂载盘运维/备份）。
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
			throw new BusinessException("目标文件写入失败", e);
		}
	}

	private void validateBodySize(String contentMd) {
		if (contentMd == null) {
			return;
		}
		long bytes = contentMd.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_BODY_BYTES) {
			throw new BadRequestException("目标详情不能超过 2MB");
		}
	}

	/** 校验并返回规范标题（非空、≤200、非空白；title 为索引字段不参与文件名，无需黑名单校验） */
	public String prepareTitle(String rawTitle) {
		if (rawTitle == null) {
			throw new BadRequestException("标题不能为空");
		}
		String title = rawTitle.trim();
		if (title.isEmpty()) {
			throw new BadRequestException("标题不能为空");
		}
		if (title.length() > MAX_TITLE_LEN) {
			throw new BadRequestException("标题长度不能超过 " + MAX_TITLE_LEN);
		}
		return title;
	}

	/**
	 * checkbox 子项（解析结果）
	 */
	@Data
	public static class SubItem {

		/** true=已勾选（- [x]）；false=未勾选（- [ ]） */
		private final boolean done;

		/** 子项文本（已 trim） */
		private final String text;

		public SubItem(boolean done, String text) {
			this.done = done;
			this.text = text;
		}
	}
}
