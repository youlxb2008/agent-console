package com.youlxb2008.admin.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;

/**
 * MinIO 客户端配置（M5.1-R1）
 * <p>
 * MinioClient 做成 @Configuration bean：endpoint + 凭据齐备时启动即做连通性快速失败
 * （fail-fast，桶不存在 / 网络不可达直接启动失败，避免运行期才发现存储不可用）；
 * 未配置时（本地裸 dev、无 MINIO_* 环境变量）构造惰性占位 client 并记 WARN，
 * 不阻断启动——上传接口运行期返回 500 明确 msg（任务书：不做本地磁盘 fallback）。
 * 单测通过 @MockBean MinioClient 替换本 bean，工厂方法不执行。
 */
@Configuration
@Slf4j
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

	/** 占位 endpoint：仅用于未配置时构造惰性 client（不发任何网络请求） */
	private static final String PLACEHOLDER_ENDPOINT = "http://127.0.0.1:9";

	/**
	 * MinioClient Bean
	 *
	 * @param props MinIO 配置（MINIO_* 环境变量注入）
	 * @return MinioClient 实例
	 */
	@Bean
	public MinioClient minioClient(MinioProperties props) {
		if (!props.isConfigured()) {
			log.warn("MinIO 未配置 endpoint/凭据（MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY 缺失），"
					+ "文件上传接口将返回 500（附件存储不可达），请由部署机 .env 注入");
			// 惰性占位 client：构建不发网络请求，仅在真正上传时连接失败
			return MinioClient.builder()
					.endpoint(PLACEHOLDER_ENDPOINT)
					.credentials("placeholder", "placeholder")
					.build();
		}

		MinioClient client = MinioClient.builder()
				.endpoint(props.getEndpoint())
				.credentials(props.getAccessKey(), props.getSecretKey())
				.build();

		// 启动 fail-fast：校验桶存在（public-read 策略由部署侧预置，见任务书）
		try {
			boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
			if (!exists) {
				throw new IllegalStateException("MinIO 桶不存在: " + props.getBucket()
						+ "（需在 MinIO 侧预建并配置 public-read 策略，三环境共用）");
			}
			log.info("MinIO 启动检查通过: endpoint={}, bucket={}, pathPrefix={}",
					props.getEndpoint(), props.getBucket(), props.getPathPrefix());
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("MinIO 启动连通性检查失败: " + e.getMessage(), e);
		}
		return client;
	}
}
