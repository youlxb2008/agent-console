package com.youlxb2008.admin.modules.system.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.youlxb2008.admin.config.DataRootConfig;
import com.youlxb2008.admin.config.MinioProperties;
import com.youlxb2008.admin.exceptions.BaseException;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一文件上传服务
 * <p>
 * 提供以下能力：
 * 1. 普通文件上传（整文件一次上传）→ 存储介质为 <b>MinIO</b>（M5.1-R1）
 * 2. 断点续传（分片上传 + 合并，支持大文件和弱网环境）→ 沿用本地磁盘临时目录（任务书影响面外，原样保留）
 * 3. 已上传分片查询（前端断点续传时，先查询哪些分片已存在，跳过已传分片）
 * <p>
 * MinIO 存储规则（M5.1-R1，任务书）：
 * - object key = {MINIO_PATH_PREFIX}/{type}/{yyyyMM}/{UUID}{extension}
 * - 上传返回 MinIO 绝对直链 {MINIO_ENDPOINT}/{MINIO_BUCKET}/{objectKey}，桶 public-read（匿名 GET 可读）
 * - 不做预签名、不做 nginx 反代、不做本地磁盘 fallback（MinIO 不可达 → 500 明确 msg）
 * <p>
 * 断点续传本地磁盘规则：
 * - 分片临时文件：{uploadTempDir}/{fileIdentifier}/{chunkIndex}，合并后返回 /uploads/ 相对 URL（兼容存量）
 * <p>
 * 断点续传流程：
 * 1. 前端计算文件 MD5 标识（fileIdentifier），并切分为等大分片
 * 2. 调用 /system/upload/chunk 接口逐片上传
 * 3. 全部分片上传完毕后，调用 /system/upload/merge 接口合并
 * 4. 合并成功后删除临时分片，返回最终文件 URL
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadService {

	/** 数据根目录配置（自动派生 uploadDir / uploadTempDir / chunkSize，断点续传本地临时目录用） */
	private final DataRootConfig dataRootConfig;

	/** MinIO 客户端（M5.1-R1：整文件上传存储介质） */
	private final MinioClient minioClient;

	/** MinIO 配置（endpoint/bucket/pathPrefix） */
	private final MinioProperties minioProperties;

	/** 允许上传的图片 MIME 类型白名单 */
	private static final Set<String> IMAGE_CONTENT_TYPES = Set.of(
			"image/jpeg",
			"image/png",
			"image/webp",
			"image/gif");

	/** 允许上传的文件扩展名白名单（包含文档、压缩包等常见安全格式） */
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
			// 图片
			"jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
			// 文档
			"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
			// 压缩包
			"zip", "rar", "7z", "tar", "gz",
			// 文本
			"txt", "csv", "json", "xml");

	/** 单个文件最大大小：50MB（断点续传场景需支持更大文件） */
	private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

	/** MinIO 对象 key 日期目录格式：yyyyMM（任务书） */
	private static final DateTimeFormatter MONTH_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	/** 断点续传合并落盘日期目录格式：yyyyMMdd（本地磁盘流程原样保留） */
	private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

	/**
	 * 普通文件上传（整文件一次上传，存储介质 MinIO）
	 * <p>
	 * 业务流程：
	 * 1. 校验文件非空、大小不超限
	 * 2. 校验文件类型（图片走 MIME 白名单，其他文件走扩展名白名单）
	 * 3. 生成 object key = {prefix}/{type}/{yyyyMM}/{UUID}{extension}
	 * 4. 上传 MinIO（桶 public-read），返回绝对直链 {endpoint}/{bucket}/{objectKey}
	 * 5. MinIO 不可达 → 抛 BaseException（全局处理器 500 + 明确 msg），不做本地磁盘 fallback
	 *
	 * @param file 上传的文件
	 * @param type 业务类型（如 avatar、login-bg、agent-{id}、general 等）
	 * @return 包含 url（MinIO 绝对直链）字段的 Map
	 */
	public Map<String, String> uploadFile(MultipartFile file, String type) {
		// 1. 文件非空校验
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("上传文件不能为空");
		}

		// 2. 文件大小校验
		if (file.getSize() > MAX_FILE_SIZE) {
			throw new IllegalArgumentException("文件大小不能超过 50MB");
		}

		// 3. 文件类型校验（图片按 MIME 白名单，其他按扩展名白名单）
		validateFileType(file);

		// 4. 生成新文件名：UUID + 原始扩展名（规则原样保留）
		String newFileName = generateFileName(file.getOriginalFilename());

		// 5. 拼 MinIO object key：{prefix}/{type}/{yyyyMM}/{fileName}
		String objectKey = buildObjectKey(type, LocalDate.now().format(MONTH_DIR_FORMATTER), newFileName);

		// 6. 上传 MinIO（不做本地磁盘 fallback，避免双真相源）
		try (InputStream in = file.getInputStream()) {
			minioClient.putObject(PutObjectArgs.builder()
					.bucket(minioProperties.getBucket())
					.object(objectKey)
					.stream(in, file.getSize(), -1)
					.contentType(file.getContentType())
					.build());
		} catch (Exception e) {
			log.error("MinIO 上传失败: type={}, objectKey={}", type, objectKey, e);
			throw new BaseException("附件存储不可达（MinIO）: " + messageOf(e), e);
		}

		// 7. 返回 MinIO 绝对直链
		String url = buildDirectUrl(objectKey);
		log.info("文件上传成功: type={}, size={}, objectKey={}, url={}", type, file.getSize(), objectKey, url);

		return Map.of("url", url);
	}

	/**
	 * 拼接 MinIO object key：{prefix}/{type}/{yyyyMM}/{fileName}
	 * <p>
	 * prefix 为空时省略前缀段（本地裸 dev 未配置 MINIO_PATH_PREFIX 的场景）。
	 *
	 * @param type     业务类型（avatar/cover/login-bg/agent-{id}/general，服务端可控）
	 * @param monthDir 月份目录（yyyyMM）
	 * @param fileName UUID 文件名（含扩展名）
	 * @return object key
	 */
	private String buildObjectKey(String type, String monthDir, String fileName) {
		String prefix = minioProperties.getPathPrefix();
		String key = type + "/" + monthDir + "/" + fileName;
		if (prefix != null && !prefix.isBlank()) {
			key = prefix + "/" + key;
		}
		return key;
	}

	/**
	 * 拼接 MinIO 绝对直链：{endpoint}/{bucket}/{objectKey}
	 *
	 * @param objectKey object key
	 * @return 匿名可读的绝对直链（桶 public-read）
	 */
	private String buildDirectUrl(String objectKey) {
		String endpoint = minioProperties.getEndpoint();
		while (endpoint.endsWith("/")) {
			endpoint = endpoint.substring(0, endpoint.length() - 1);
		}
		return endpoint + "/" + minioProperties.getBucket() + "/" + objectKey;
	}

	/**
	 * 提取异常简短信息（避免 null message 场景）
	 *
	 * @param e 异常
	 * @return message 或异常类名
	 */
	private String messageOf(Throwable e) {
		String m = e.getMessage();
		if (m == null || m.isBlank()) {
			return e.getClass().getSimpleName();
		}
		// 截断过长的 MinIO SDK 错误详情（如 XML 响应），保留关键信息
		return m.length() > 300 ? m.substring(0, 300) + "..." : m;
	}

	/**
	 * 上传分片（断点续传）
	 * <p>
	 * 每个分片存储在临时目录：{uploadTempDir}/{fileIdentifier}/{chunkIndex}
	 * 如果该分片已存在（重复上传或断点续传），直接返回成功，不重复写入。
	 *
	 * @param file           分片文件数据
	 * @param fileIdentifier 文件唯一标识（前端根据文件内容计算的 MD5）
	 * @param chunkIndex     当前分片序号（从 0 开始）
	 * @param totalChunks    总分片数
	 * @param fileName       原始文件名
	 * @param type           业务类型
	 * @return 包含已上传分片信息的 Map
	 */
	public Map<String, Object> uploadChunk(MultipartFile file, String fileIdentifier,
			int chunkIndex, int totalChunks, String fileName, String type) {
		// 1. 参数校验
		if (file == null || file.isEmpty()) {
			throw new IllegalArgumentException("分片文件不能为空");
		}
		if (fileIdentifier == null || fileIdentifier.isBlank()) {
			throw new IllegalArgumentException("文件标识不能为空");
		}
		if (chunkIndex < 0 || chunkIndex >= totalChunks) {
			throw new IllegalArgumentException("分片序号不合法");
		}

		// 2. 分片临时目录：{uploadTempDir}/{fileIdentifier}/
		File chunkDir = ensureDir(dataRootConfig.getUploadTempDir(), fileIdentifier);

		// 3. 分片文件：{chunkDir}/{chunkIndex}
		File chunkFile = new File(chunkDir, String.valueOf(chunkIndex));

		// 4. 如果分片已存在（重复上传或断点续传），直接跳过
		if (chunkFile.exists() && chunkFile.length() == file.getSize()) {
			log.debug("分片已存在，跳过: fileIdentifier={}, chunkIndex={}", fileIdentifier, chunkIndex);
			return Map.of(
					"fileIdentifier", fileIdentifier,
					"chunkIndex", chunkIndex,
					"status", "exists");
		}

		// 5. 写入分片
		transferFile(file, chunkFile.getAbsoluteFile());
		log.debug("分片上传成功: fileIdentifier={}, chunkIndex={}/{}", fileIdentifier, chunkIndex, totalChunks - 1);

		// 6. 保存元数据（原始文件名、业务类型、总分片数），供合并时使用
		writeMetadata(chunkDir, fileName, type, totalChunks);

		return Map.of(
				"fileIdentifier", fileIdentifier,
				"chunkIndex", chunkIndex,
				"status", "uploaded");
	}

	/**
	 * 合并分片（断点续传最后一步）
	 * <p>
	 * 业务流程：
	 * 1. 读取元数据获取原始文件名、业务类型、总分片数
	 * 2. 校验全部分片是否已上传完毕
	 * 3. 按序合并所有分片到最终文件
	 * 4. 删除临时分片和元数据
	 * 5. 返回最终文件 URL
	 *
	 * @param fileIdentifier 文件唯一标识
	 * @return 包含 url 字段的 Map
	 */
	public Map<String, String> mergeChunks(String fileIdentifier) {
		// 1. 读取临时目录和元数据
		File chunkDir = new File(dataRootConfig.getUploadTempDir(), fileIdentifier);
		if (!chunkDir.exists() || !chunkDir.isDirectory()) {
			throw new IllegalArgumentException("文件标识不存在，请重新上传");
		}

		Map<String, String> metadata = readMetadata(chunkDir);
		String fileName = metadata.get("fileName");
		String type = metadata.getOrDefault("type", "general");
		int totalChunks = Integer.parseInt(metadata.getOrDefault("totalChunks", "0"));

		if (totalChunks == 0) {
			throw new IllegalArgumentException("分片元数据异常，请重新上传");
		}

		// 2. 校验全部分片是否已上传完毕
		for (int i = 0; i < totalChunks; i++) {
			File chunkFile = new File(chunkDir, String.valueOf(i));
			if (!chunkFile.exists()) {
				throw new IllegalArgumentException("分片不完整，缺少第 " + i + " 片，请继续上传");
			}
		}

		// 3. 计算最终文件存储路径
		String dateDir = LocalDate.now().format(DATE_DIR_FORMATTER);
		File destDir = ensureDir(dataRootConfig.getUploadDir(), type, dateDir);
		String newFileName = generateFileName(fileName);
		File destFile = new File(destDir, newFileName).getAbsoluteFile();

		// 4. 按序合并所有分片
		try (FileOutputStream fos = new FileOutputStream(destFile)) {
			for (int i = 0; i < totalChunks; i++) {
				File chunkFile = new File(chunkDir, String.valueOf(i));
				Files.copy(chunkFile.toPath(), fos);
			}
		} catch (IOException e) {
			log.error("分片合并失败: fileIdentifier={}, destFile={}", fileIdentifier, destFile.getAbsolutePath(), e);
			// 合并失败时删除不完整的文件
			destFile.delete();
			throw new RuntimeException("分片合并失败", e);
		}

		log.info("分片合并成功: fileIdentifier={}, totalChunks={}, destFile={}", fileIdentifier, totalChunks, destFile.getAbsolutePath());

		// 5. 合并成功后删除临时分片目录（含分片文件和元数据）
		deleteDirectory(chunkDir);

		// 6. 返回最终文件 URL
		String url = "/uploads/" + type + "/" + dateDir + "/" + newFileName;
		return Map.of("url", url);
	}

	/**
	 * 查询已上传的分片列表（断点续传恢复用）
	 * <p>
	 * 前端在恢复上传时，先调用此接口查询已上传的分片序号，
	 * 跳过已传分片，仅上传缺失的分片，实现断点续传。
	 *
	 * @param fileIdentifier 文件唯一标识
	 * @return 包含 uploadedChunks（已上传分片序号列表）的 Map
	 */
	public Map<String, Object> getUploadedChunks(String fileIdentifier) {
		File chunkDir = new File(dataRootConfig.getUploadTempDir(), fileIdentifier);

		// 如果临时目录不存在，说明该文件从未上传过分片
		if (!chunkDir.exists() || !chunkDir.isDirectory()) {
			return Map.of(
					"fileIdentifier", fileIdentifier,
					"uploadedChunks", List.of());
		}

		// 遍历临时目录中的分片文件，收集已上传的分片序号
		List<Integer> uploadedChunks = new ArrayList<>();
		File[] files = chunkDir.listFiles();
		if (files != null) {
			for (File f : files) {
				// 分片文件以序号命名（0, 1, 2, ...），忽略元数据文件
				if (!f.getName().equals(".meta") && f.isFile()) {
					try {
						uploadedChunks.add(Integer.parseInt(f.getName()));
					} catch (NumberFormatException e) {
						// 非数字文件名跳过（如 .meta 文件）
						log.warn("临时目录中存在非分片文件: {}", f.getName());
					}
				}
			}
		}

		// 按序号排序，方便前端判断
		uploadedChunks.sort(Comparator.naturalOrder());

		return Map.of(
				"fileIdentifier", fileIdentifier,
				"uploadedChunks", uploadedChunks);
	}

	// ========== 私有辅助方法 ==========

	/**
	 * 校验文件类型
	 * <p>
	 * 图片类型走 MIME 白名单校验，非图片类型走扩展名白名单校验。
	 * 双重校验确保安全性。
	 *
	 * @param file 上传的文件
	 */
	private void validateFileType(MultipartFile file) {
		String contentType = file.getContentType();
		String originalFilename = file.getOriginalFilename();

		// 如果 MIME 类型是图片，走图片白名单
		if (contentType != null && IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase())) {
			return;
		}

		// 非图片文件，校验扩展名是否在白名单内
		if (originalFilename != null && originalFilename.contains(".")) {
			String ext = originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();
			if (ALLOWED_EXTENSIONS.contains(ext)) {
				return;
			}
		}

		throw new IllegalArgumentException("不支持的文件类型，仅允许图片和常见文档格式");
	}

	/**
	 * 确保目录存在，不存在则递归创建
	 *
	 * @param parent 父目录
	 * @param subDirs 子目录层级
	 * @return 最终目录 File 对象
	 */
	private File ensureDir(String parent, String... subDirs) {
		File dir = new File(parent);
		for (String sub : subDirs) {
			dir = new File(dir, sub);
		}
		if (!dir.exists() && !dir.mkdirs()) {
			throw new RuntimeException("创建目录失败: " + dir.getAbsolutePath());
		}
		return dir;
	}

	/**
	 * 生成新文件名：UUID（去除横线）+ 原始扩展名
	 *
	 * @param originalFilename 原始文件名
	 * @return 新文件名
	 */
	private String generateFileName(String originalFilename) {
		String extension = "";
		if (originalFilename != null && originalFilename.contains(".")) {
			// 取最后一个点之后的内容作为扩展名，并转小写保持统一
			extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
		}
		return UUID.randomUUID().toString().replace("-", "") + extension;
	}

	/**
	 * 安全地将 MultipartFile 写入目标文件
	 *
	 * @param file     上传的文件
	 * @param destFile 目标文件（绝对路径）
	 */
	private void transferFile(MultipartFile file, File destFile) {
		try {
			file.transferTo(destFile);
		} catch (IOException e) {
			log.error("文件写入磁盘失败: {}", destFile.getAbsolutePath(), e);
			throw new RuntimeException("文件写入失败", e);
		}
	}

	/**
	 * 写入分片元数据文件（.meta）
	 * <p>
	 * 元数据格式为简单的 key=value 行，供合并时读取。
	 *
	 * @param chunkDir    分片临时目录
	 * @param fileName    原始文件名
	 * @param type        业务类型
	 * @param totalChunks 总分片数
	 */
	private void writeMetadata(File chunkDir, String fileName, String type, int totalChunks) {
		File metaFile = new File(chunkDir, ".meta");
		try {
			// 使用简单文本格式写入，避免引入 JSON 依赖
			String content = "fileName=" + (fileName != null ? fileName : "") + "\n"
					+ "type=" + (type != null ? type : "general") + "\n"
					+ "totalChunks=" + totalChunks + "\n";
			Files.writeString(metaFile.toPath(), content);
		} catch (IOException e) {
			log.error("写入分片元数据失败: {}", metaFile.getAbsolutePath(), e);
		}
	}

	/**
	 * 读取分片元数据文件
	 *
	 * @param chunkDir 分片临时目录
	 * @return key-value 形式的元数据 Map
	 */
	private Map<String, String> readMetadata(File chunkDir) {
		File metaFile = new File(chunkDir, ".meta");
		if (!metaFile.exists()) {
			return Map.of();
		}
		try {
			List<String> lines = Files.readAllLines(metaFile.toPath());
			Map<String, String> metadata = new java.util.HashMap<>();
			for (String line : lines) {
				int eq = line.indexOf('=');
				if (eq > 0) {
					metadata.put(line.substring(0, eq), line.substring(eq + 1));
				}
			}
			return metadata;
		} catch (IOException e) {
			log.error("读取分片元数据失败: {}", metaFile.getAbsolutePath(), e);
			return Map.of();
		}
	}

	/**
	 * 递归删除目录及其内容
	 *
	 * @param directory 要删除的目录
	 */
	private void deleteDirectory(File directory) {
		if (!directory.exists()) return;
		File[] files = directory.listFiles();
		if (files != null) {
			for (File f : files) {
				if (f.isDirectory()) {
					deleteDirectory(f);
				} else {
					f.delete();
				}
			}
		}
		directory.delete();
	}
}
