package com.youlxb2008.admin;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 管理端 后端启动入口
 * <p>
 * 启动后会在日志中统一打印所有对外暴露的服务地址（Swagger / Actuator / H2 / Druid），
 * 避免开发人员每次都要去 yml 里找端口和路径。
 */
@Slf4j
@SpringBootApplication
@EnableCaching
@MapperScan("com.youlxb2008.admin.**.mapper")
public class WebApplication {
	public static void main(String[] args) {
		SpringApplication.run(WebApplication.class, args);
	}

	/**
	 * 应用启动完成后的监听器：统一打印所有对外暴露的服务地址
	 * <p>
	 * 通过 ApplicationReadyEvent 而不是 main 方法保证：
	 * 1. Spring 容器完全就绪后再打印
	 * 2. 端口 / 路径从 Environment 读取，支持配置覆盖
	 * 3. 根据 profile 区分打印（dev 多打印 H2，prod 多打印 Druid）
	 */
	@Slf4j
	@Component
	@RequiredArgsConstructor
	public static class StartupInfoPrinter {

		private final Environment env;

		@EventListener(ApplicationReadyEvent.class)
		public void printStartupInfo() {
			// 1. 读取基础信息
			String serverPort = env.getProperty("server.port", "8000");
			String contextPath = env.getProperty("server.servlet.context-path", "/");
			// 保证 contextPath 以 / 开头并以 / 结尾（拼接方便）
			if (!contextPath.startsWith("/")) {
				contextPath = "/" + contextPath;
			}
			if (!contextPath.endsWith("/")) {
				contextPath = contextPath + "/";
			}

			// 2. 解析 host（拿不到就用 localhost）
			String host;
			try {
				host = InetAddress.getLocalHost().getHostAddress();
			} catch (Exception e) {
				host = "localhost";
			}

			String baseUrl = "http://" + host + ":" + serverPort + contextPath;

			// 3. 按分组组织所有对外暴露的服务（保持插入顺序）
			Map<String, String> services = new LinkedHashMap<>();

			// 3.1 API 文档（SpringDoc / Swagger UI）
			String swaggerPath = env.getProperty("springdoc.swagger-ui.path", "/swagger/");
			services.put("Swagger UI", baseUrl + swaggerPath.replaceAll("^/", ""));

			// 3.2 健康检查（Actuator）
			// management.endpoints.web.exposure.include 决定是否暴露
			String exposed = env.getProperty("management.endpoints.web.exposure.include", "");
			if (exposed.contains("health")) {
				services.put("健康检查(health)", baseUrl + "actuator/health");
			}
			if (exposed.contains("info")) {
				services.put("应用信息(info)", baseUrl + "actuator/info");
			}

			// 3.3 profile 相关端点（根据当前 profile 打印）
			String[] activeProfiles = env.getActiveProfiles();
			String profile = activeProfiles.length > 0 ? activeProfiles[0] : "dev";

			if ("dev".equals(profile)) {
				// dev：H2 Console（Druid 在 if 外统一处理）
				Boolean h2Enabled = env.getProperty("spring.h2.console.enabled", Boolean.class, false);
				if (h2Enabled) {
					String h2Path = env.getProperty("spring.h2.console.settings.path", "/h2-console");
					// 读取 H2 JDBC URL（用于预填登录表单）
					// dynamic-datasource 配置路径优先，fallback 到普通 spring.datasource.url
					String h2JdbcUrl = env.getProperty(
							"spring.datasource.dynamic.datasource.primary.url",
							env.getProperty("spring.datasource.url", ""));
					// 生成带预填参数的 H2 Console 链接
					// 注意：JDBC URL 必须用 URLEncoder 编码，否则 Tomcat 会把 ';' 当作 path 参数分隔符
					String h2FullUrl = baseUrl + h2Path.replaceAll("^/", "")
							+ "/?driver=org.h2.Driver"
							+ "&url=" + URLEncoder.encode(h2JdbcUrl, StandardCharsets.UTF_8)
							+ "&user=sa&password=";
					services.put("H2 控制台(已预填)", h2FullUrl);
				}
			}

			// Druid 监控页（dev / prod 均可能开启，统一检查）
			// 配置路径：spring.datasource.dynamic.druid.stat-view-servlet.enabled（dynamic-datasource
			// 格式）
			Boolean druidEnabled = env.getProperty(
					"spring.datasource.dynamic.druid.stat-view-servlet.enabled", Boolean.class, false);
			if (druidEnabled) {
				services.put("Druid 监控", baseUrl + "druid/index.html");
			}

			// 3.4 前端访问地址（与 frontend/vite.config.ts 中的 server.port 保持一致）
			// 默认 12388，可通过 web.frontend-port 配置覆盖
			String frontendPort = env.getProperty("web.frontend-port", "12388");
			String frontendBaseUrl = "http://" + host + ":" + frontendPort + "/";
			services.put("前端地址", frontendBaseUrl);

			// 4. 打印启动横幅（多行一次性输出，避免日志被其他组件打断）
			StringBuilder sb = new StringBuilder();
			sb.append("\n");
			sb.append("==============================================================\n");
			sb.append("  管理端 后端启动完成 (profile: ").append(profile).append(")\n");
			sb.append("--------------------------------------------------------------\n");
			// 左对齐：服务名占位宽度（取最长名称，至少 10）
			// 注意：必须在 lambda 外计算完成，且变量需要是 effectively final
			final int nameWidth = Math.max(
					services.keySet().stream().mapToInt(String::length).max().orElse(10),
					10);
			services.forEach((name, url) -> sb.append("  ")
					.append(String.format("%-" + nameWidth + "s : %s%n", name, url)));
			sb.append("--------------------------------------------------------------\n");
			sb.append("  Base URL : ").append(baseUrl).append("\n");
			sb.append("==============================================================");

			log.info(sb.toString());
		}
	}
}
