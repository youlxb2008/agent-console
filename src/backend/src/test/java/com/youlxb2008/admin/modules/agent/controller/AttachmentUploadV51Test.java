package com.youlxb2008.admin.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.agent.filter.ApiKeyAuthFilter;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;

import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import jakarta.annotation.Resource;

/**
 * M5.1-R1 agent 域图片上传接口（MinIO 存储）— 路由级集成测试（MockMvc + 独立内存 H2）
 * <p>
 * 对照任务书 docs/任务排期/M5.1-agent-attachments.md（修订版）验收口径：
 * <ul>
 *   <li>T_att_01 上传成功 → 200，object key 拼接 {prefix}/agent-{id}/{yyyyMM}/{uuid}.png，返回 MinIO 绝对直链，fileName/size 正确</li>
 *   <li>T_att_02 非图片类型（非图片 MIME 且扩展名不在白名单）→ 400</li>
 *   <li>T_att_03 未带 X-API-Key → 401（ApiKeyAuthFilter）</li>
 *   <li>T_att_04 scope 不含 notes:write → 403（ScopeAspect）</li>
 *   <li>T_att_05 MinIO 不可达 → 500 且 msg 含 MinIO 明确提示（不做本地磁盘 fallback）</li>
 * </ul>
 * MinioClient 用 @MockBean 替换（单测不连真实 MinIO），断言 putObject 参数与直链格式；
 * 独立内存 H2 + 临时数据目录，不触碰开发库/真实数据；全部 @Transactional 回滚。
 */
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcatt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录（含 temp/）落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-att",
		// MinIO mock 配置：单测不连真实 MinIO（MinioClient 由 @MockBean 替换，工厂方法不执行）
		"minio.endpoint=http://minio.example:9000",
		"minio.access-key=test-access",
		"minio.secret-key=test-secret",
		"minio.bucket=agent-console-attachment",
		"minio.path-prefix=x7k" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class AttachmentUploadV51Test {

	/** 1x1 透明 PNG（最小合法 PNG 字节） */
	private static final byte[] PNG_BYTES = new byte[] {
			(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
			0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
			0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
			0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
			(byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
			0x54, 0x78, (byte) 0x9C, 0x62, 0x00, 0x01, 0x00, 0x00,
			0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
			0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
			0x42, 0x60, (byte) 0x82 };

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Resource
	private AgentService agentService;

	@Resource
	private ApiKeyService apiKeyService;

	/** MinIO 客户端 mock：单测不连真实 MinIO，仅验证 putObject 参数与直链拼接 */
	@MockBean
	private MinioClient minioClient;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent agent;
	private String plainKey;

	@BeforeEach
	void seed() throws Exception {
		String tag = "att" + SEQ.incrementAndGet();
		agent = agentService.createAgent("ag_" + tag, "显示" + tag, null, "测试 agent", 1, 1L);
		// agent 域接口需要有效 X-API-Key（scopes 覆盖 notes:write）
		plainKey = apiKeyService.generateKey(agent.getId(),
				List.of("notes:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
		// 默认 stub：putObject 成功（返回 mock ObjectWriteResponse）
		when(minioClient.putObject(any(PutObjectArgs.class)))
				.thenReturn(mock(ObjectWriteResponse.class));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dataOf(MvcResult result) throws Exception {
		return (Map<String, Object>) objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class).get("data");
	}

	// ===================================================================
	// T_att_01 上传成功 → 200，object key = {prefix}/agent-{id}/{yyyyMM}/{uuid}.png，
	//          返回 MinIO 绝对直链 {endpoint}/{bucket}/{objectKey}，fileName/size 正确
	// ===================================================================
	@Test
	@DisplayName("T_att_01 上传 png → 200，object key 拼接正确，返回 MinIO 绝对直链")
	void t_att01_uploadSuccess() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", PNG_BYTES);
		MvcResult result = mockMvc.perform(multipart("/api/agent/attachments")
				.file(file)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey))
				.andExpect(status().isOk())
				.andReturn();
		Map<String, Object> data = dataOf(result);
		assertNotNull(data, "data 不应为 null");

		// 捕获 putObject 参数，断言 object key 拼接（任务书：{MINIO_PATH_PREFIX}/{type}/{yyyyMM}/{fileName}）
		ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
		verify(minioClient).putObject(captor.capture());
		PutObjectArgs args = captor.getValue();
		assertNotNull(args, "putObject 参数不应为 null");
		assertEquals("agent-console-attachment", args.bucket(), "bucket 应为固定桶名");
		assertEquals("image/png", args.contentType(), "contentType 应透传原始 MIME");
		String objectKey = args.object();
		assertNotNull(objectKey, "object key 不应为 null");
		assertTrue(objectKey.matches("^x7k/agent-" + agent.getId() + "/\\d{6}/[0-9a-f]{32}\\.png$"),
				"object key 应为 {prefix}/agent-{id}/{yyyyMM}/{uuid}.png，实际: " + objectKey);

		// 返回 MinIO 绝对直链
		String url = (String) data.get("url");
		assertNotNull(url, "url 不应为 null");
		assertEquals("http://minio.example:9000/agent-console-attachment/" + objectKey,
				url, "url 应为 MinIO 绝对直链 {endpoint}/{bucket}/{objectKey}");
		assertEquals("test.png", data.get("fileName"), "fileName 应为原始文件名");
		assertEquals((long) PNG_BYTES.length, ((Number) data.get("size")).longValue(), "size 应为文件字节数");
	}

	// ===================================================================
	// T_att_02 非图片类型（非图片 MIME 且扩展名不在白名单）→ 400
	// ===================================================================
	@Test
	@DisplayName("T_att_02 上传 .exe（非图片 MIME + 扩展名不在白名单）→ 400")
	void t_att02_nonImageBadRequest() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream",
				new byte[] { 0x4D, 0x5A });
		mockMvc.perform(multipart("/api/agent/attachments")
				.file(file)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey))
				.andExpect(status().isBadRequest());
	}

	// ===================================================================
	// T_att_03 未带 X-API-Key → 401（ApiKeyAuthFilter）
	// ===================================================================
	@Test
	@DisplayName("T_att_03 未带 X-API-Key → 401")
	void t_att03_missingKeyUnauthorized() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", PNG_BYTES);
		mockMvc.perform(multipart("/api/agent/attachments").file(file))
				.andExpect(status().isUnauthorized());
	}

	// ===================================================================
	// T_att_04 scope 不含 notes:write → 403（ScopeAspect）
	// ===================================================================
	@Test
	@DisplayName("T_att_04 key 仅含 audit:read（无 notes:write）→ 403")
	void t_att04_scopeForbidden() throws Exception {
		String auditOnlyKey = apiKeyService.generateKey(agent.getId(),
				List.of("audit:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", PNG_BYTES);
		mockMvc.perform(multipart("/api/agent/attachments")
				.file(file)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, auditOnlyKey))
				.andExpect(status().isForbidden());
	}

	// ===================================================================
	// T_att_05 MinIO 不可达 → 500 且 msg 含 MinIO 明确提示（不做本地磁盘 fallback）
	// ===================================================================
	@Test
	@DisplayName("T_att_05 MinIO 不可达 → 500，msg 含 MinIO 明确提示（无本地磁盘 fallback）")
	void t_att05_minioUnreachable() throws Exception {
		when(minioClient.putObject(any(PutObjectArgs.class)))
				.thenThrow(new IOException("Connection refused: minio.example:9000"));
		MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", PNG_BYTES);
		MvcResult result = mockMvc.perform(multipart("/api/agent/attachments")
				.file(file)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey))
				.andExpect(status().isInternalServerError())
				.andReturn();
		String body = result.getResponse().getContentAsString();
		assertTrue(body.contains("MinIO"),
				"500 响应应包含 MinIO 明确提示（不做本地磁盘 fallback），实际: " + body);
		assertTrue(body.contains("附件存储不可达"),
				"500 响应应包含「附件存储不可达」前缀，实际: " + body);
	}
}
