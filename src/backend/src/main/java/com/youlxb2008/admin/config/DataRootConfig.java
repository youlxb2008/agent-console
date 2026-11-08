package com.youlxb2008.admin.config;

import java.io.File;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据根目录配置
 * <p>
 * 所有本地数据目录从 data-root 派生，
 * 各子目录也支持单独覆盖。
 * <p>
 * 派生规则：
 * <pre>
 * data-root (由 DATA_ROOT 环境变量或 yml 配置)
 *   ├── uploads/     上传文件目录       = data-root + /uploads    （可 upload-dir 覆盖）
 *   ├── temp/        断点续传临时目录   = data-root + /temp       （可 upload-temp-dir 覆盖）
 *   └── db/          H2 数据库目录      = data-root + /db         （仅 dev profile 使用）
 * </pre>
 * <p>
 * Docker 部署只需挂载一个卷并设置 DATA_ROOT 即可，所有数据自动落入同一目录下。
 * 也可通过环境变量单独覆盖某个子目录（如日志挂到独立磁盘）。
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "web")
public class DataRootConfig {

	/**
	 * 数据根目录
	 * <p>
	 * 默认值 ./data，各 profile 覆盖：
	 * - 默认: /data/agent-console-data
	 * 也可通过环境变量 DATA_ROOT 覆盖
	 */
	private String dataRoot = "./data";

	/**
	 * 上传文件目录（默认从 data-root 派生）
	 * <p>
	 * 如果显式配置了此值，则使用配置值；否则从 data-root/uploads 派生
	 */
	private String uploadDir;

	/**
	 * 断点续传临时目录（默认从 data-root 派生）
	 * <p>
	 * 如果显式配置了此值，则使用配置值；否则从 data-root/temp 派生
	 */
	private String uploadTempDir;

	/**
	 * 分片大小（字节），默认 2MB
	 */
	private long chunkSize = 2L * 1024 * 1024;

	/**
	 * 应用启动后，初始化所有必要的目录并打印配置信息
	 */
	@PostConstruct
	public void init() {
		// 确保根目录存在
		ensureDir(getDataRootPath());

		// 派生上传目录（如未单独配置）
		if (uploadDir == null || uploadDir.isBlank()) {
			uploadDir = getDataRootPath() + File.separator + "uploads";
		}
		ensureDir(uploadDir);

		// 派生临时目录（如未单独配置）
		if (uploadTempDir == null || uploadTempDir.isBlank()) {
			uploadTempDir = getDataRootPath() + File.separator + "temp";
		}
		ensureDir(uploadTempDir);

		// 派生数据库目录（dev H2 模式使用）
		String dbDir = getDataRootPath() + File.separator + "db";
		ensureDir(dbDir);

		// 启动时打印数据目录结构，方便排查
		log.info("数据根目录初始化完成:");
		log.info("  data-root     = {}", getDataRootPath());
		log.info("  uploads       = {}", uploadDir);
		log.info("  temp          = {}", uploadTempDir);
		log.info("  db            = {}", dbDir);
		log.info("  chunk-size    = {} bytes ({}MB)", chunkSize, chunkSize / 1024 / 1024);
	}

	/**
	 * 获取解析后的数据根目录绝对路径
	 *
	 * @return 绝对路径字符串
	 */
	public String getDataRootPath() {
		return new File(dataRoot).getAbsolutePath();
	}

	/**
	 * 确保目录存在，不存在则递归创建
	 *
	 * @param dirPath 目录路径
	 */
	private void ensureDir(String dirPath) {
		File dir = new File(dirPath);
		if (!dir.exists() && !dir.mkdirs()) {
			log.error("创建目录失败: {}", dir.getAbsolutePath());
			throw new RuntimeException("创建目录失败: " + dir.getAbsolutePath());
		}
	}
}
