package com.youlxb2008.admin.modules.ledger.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.ledger.service.LedgerService;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * 台账正文文件存储 Manager（M4.2，参照 ObjectiveStorageManager/NoteStorageManager 模式）
 * <p>
 * body 双轨制：≤500 字存 body_inline（不落文件）；>500 字写
 * {@code LEDGER_ROOT/agent-{id}/ledger-{lid}.md} + 存 body_path（相对路径入库）。
 * 一流水一文件，agent 私域目录隔离。
 * <ul>
 *   <li>写侧：lid 格式强校验 + resolve 后必须落在 LEDGER_ROOT 内（fail-closed）+ 原子写（临时文件 + move）+ 显式 644</li>
 *   <li>删除：软删保留文件（is_deleted=1，文件不删）；PATCH 长正文变短正文时旧 md 也保留（幂等安全）</li>
 *   <li>字判定：按字符数（String.length）计「500 字」；md 文件整体 ≤2MB 上限</li>
 * </ul>
 */
@Slf4j
@Component
public class LedgerStorageManager {

	/** 对外引用键前缀 l_ + 10 位 base62 */
	private static final String LID_PREFIX = "l_";
	private static final int LID_RANDOM_LEN = 10;
	private static final int LID_RETRY = 3;
	private static final Pattern LID_PATTERN = Pattern.compile("^l_[0-9A-Za-z]{10}$");
	private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static final SecureRandom RANDOM = new SecureRandom();

	/** title ≤200 字符 */
	private static final int MAX_TITLE_LEN = 200;
	/** 长正文 md 整体 ≤2MB */
	private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;
	/** body 双轨阈值：>500 字写 md（按字符数计） */
	private static final int INLINE_MAX_CHARS = 500;

	@Value("${ledger.root:./data/ledger}")
	private String ledgerRoot;

	/** lid 去重（findByLid 空占位；@Lazy 打破与 LedgerService 的循环依赖，运行时方法调用互依） */
	@org.springframework.context.annotation.Lazy
	@org.springframework.beans.factory.annotation.Autowired
	private LedgerService ledgerService;

	/**
	 * 启动即建根目录 LEDGER_ROOT/（首次写文件时 atomicWrite 会再 createDirectories 幂等兜底）
	 */
	@PostConstruct
	public void initLedgerRoot() {
		try {
			Files.createDirectories(rootDir());
		} catch (IOException e) {
			log.warn("LEDGER_ROOT 目录初始化失败（首次写入时重试）: {}", e.getMessage());
		}
	}

	// ============================================================
	// body 双轨判定
	// ============================================================

	/**
	 * 是否应写 md 文件（>500 字走文件；null/空/≤500 字走 body_inline）
	 *
	 * @param body 正文（可 null）
	 * @return true=写 md 文件存 body_path；false=存 body_inline
	 */
	public boolean shouldWriteFile(String body) {
		return body != null && body.length() > INLINE_MAX_CHARS;
	}

	/**
	 * 写长正文 md 落盘
	 *
	 * @param agentId 归属 agent（目录 agent-{id}）
	 * @param lid     对外引用键（已生成未占用）
	 * @param body    长正文（>500 字）
	 * @return body_path 相对路径 {@code agent-{id}/ledger-{lid}.md}
	 */
	public String writeNewBody(Long agentId, String lid, String body) {
		validateBodySize(body);
		atomicWrite(resolveLedgerFile(agentId, lid), body);
		return relativePath(agentId, lid);
	}

	/**
	 * 覆写长正文 md（PATCH 长→长场景，lid 不变）
	 *
	 * @param bodyPath body_path 相对路径
	 * @param body     新长正文（>500 字）
	 */
	public void overwriteBody(String bodyPath, String body) {
		validateBodySize(body);
		atomicWrite(resolveByBodyPath(bodyPath), body);
	}

	// ============================================================
	// read：按 body_path 读原文（fail-closed）
	// ============================================================

	/**
	 * 读长正文 md 原文
	 *
	 * @param bodyPath body_path 相对路径（agent-{id}/ledger-{lid}.md）
	 * @return Markdown 原文；文件缺失 → 404（孤儿索引行）
	 */
	public String readRaw(String bodyPath) {
		Path target = resolveByBodyPath(bodyPath);
		if (!Files.isRegularFile(target)) {
			throw new NotFoundException("台账正文不存在");
		}
		try {
			return Files.readString(target, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new NotFoundException("台账正文不存在");
		}
	}

	// ============================================================
	// lid 生成 / 校验
	// ============================================================

	/** 生成未被占用的 lid（查索引去重；理论碰撞概率 62^10 分之一，仍兜底循环） */
	public String generateUniqueLid() {
		for (int i = 0; i < LID_RETRY; i++) {
			String lid = generateLid();
			if (ledgerService.findByLid(lid) == null) {
				return lid;
			}
			log.warn("lid 已被占用，重新生成 {}/{}", i + 1, LID_RETRY);
		}
		throw new BusinessException("lid 生成冲突，请重试");
	}

	private String generateLid() {
		StringBuilder sb = new StringBuilder(LID_PREFIX);
		for (int i = 0; i < LID_RANDOM_LEN; i++) {
			sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
		}
		return sb.toString();
	}

	/** lid 格式强校验（^l_[0-9A-Za-z]{10}$，防路径注入/防枚举）；不合法返回 false（调用方按 404 处理） */
	public boolean isLidValid(String lid) {
		return lid != null && LID_PATTERN.matcher(lid).matches();
	}

	/** 校验并返回规范标题（非空、≤200、非空白） */
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

	// ============================================================
	// 内部：路径 / 文件 IO / 校验
	// ============================================================

	/** LEDGER_ROOT 绝对规范路径 */
	private Path rootDir() {
		return Paths.get(ledgerRoot).toAbsolutePath().normalize();
	}

	/** body_path 相对路径：agent-{id}/ledger-{lid}.md */
	private String relativePath(Long agentId, String lid) {
		return "agent-" + agentId + "/ledger-" + lid + ".md";
	}

	/**
	 * (agentId, lid) → 文件路径（fail-closed：lid 格式强校验 + normalize 后必须仍在 root 内）
	 */
	private Path resolveLedgerFile(Long agentId, String lid) {
		if (agentId == null || !isLidValid(lid)) {
			throw new NotFoundException("台账不存在");
		}
		Path base = rootDir();
		Path target = base.resolve("agent-" + agentId).resolve("ledger-" + lid + ".md").normalize();
		if (!target.startsWith(base)) {
			throw new NotFoundException("台账不存在");
		}
		return target;
	}

	/**
	 * body_path → 文件路径（fail-closed：目录段仅允许数字 id + ledger-{lid}.md，normalize 越界按不存在处理）
	 */
	private Path resolveByBodyPath(String bodyPath) {
		if (bodyPath == null || bodyPath.isBlank()) {
			throw new NotFoundException("台账正文不存在");
		}
		Path base = rootDir();
		Path target = base.resolve(bodyPath).normalize();
		if (!target.startsWith(base)) {
			throw new NotFoundException("台账正文不存在");
		}
		return target;
	}

	/** 原子写：临时文件 + move(ATOMIC_MOVE, REPLACE_EXISTING)，避免半写被读到 */
	private void atomicWrite(Path target, String content) {
		try {
			Files.createDirectories(target.getParent());
			Path tmp = Files.createTempFile(target.getParent(), ".agc-", ".tmp");
			// createTempFile 默认 600，显式 644 保证宿主运维账号直读（挂载盘运维/备份）。
			if (tmp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
				Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
			}
			try {
				byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
				Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} finally {
				Files.deleteIfExists(tmp);
			}
		} catch (IOException e) {
			throw new BusinessException("台账正文写入失败", e);
		}
	}

	private void validateBodySize(String body) {
		if (body == null) {
			return;
		}
		long bytes = body.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > MAX_BODY_BYTES) {
			throw new BadRequestException("台账正文不能超过 2MB");
		}
	}
}
