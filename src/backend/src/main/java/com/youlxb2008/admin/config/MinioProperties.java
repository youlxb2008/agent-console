package com.youlxb2008.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * MinIO 存储配置（M5.1-R1）
 * <p>
 * 由部署机 .env 注入（MINIO_* 环境变量，见 application.yml 占位符），真实凭据不入库、不进代码库。
 * 桶固定 {@code agent-console-attachment}，三环境共用；path-prefix 为环境语义无关前缀
 * （dev=x7k / staging=m2p / prod=a9b，遵守「URL 不暴露环境标识」规范）。
 */
@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

	/** MinIO 服务地址，如 http://localhost:9000 */
	private String endpoint;

	/** 访问密钥（MinIO 侧专门 service account，限单桶 readwrite，非 root 凭据） */
	private String accessKey;

	/** 访问密钥（MinIO 侧专门 service account，限单桶 readwrite，非 root 凭据） */
	private String secretKey;

	/** 桶名（三环境共用，固定 agent-console-attachment） */
	private String bucket;

	/** 对象 key 前缀（环境语义无关 base62 前缀） */
	private String pathPrefix;

	/**
	 * 是否已显式配置 endpoint + 凭据
	 * <p>
	 * 配置齐备时启动做连通性 fail-fast；未配置（本地裸 dev/单测）只记 WARN，
	 * 上传接口运行期返回 500 明确 msg（不做本地磁盘 fallback）。
	 *
	 * @return true=齐备
	 */
	public boolean isConfigured() {
		return endpoint != null && !endpoint.isBlank()
				&& accessKey != null && !accessKey.isBlank()
				&& secretKey != null && !secretKey.isBlank();
	}
}
