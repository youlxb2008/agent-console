package com.youlxb2008.admin.modules.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.bus.service.BusRateLimiter;
import com.youlxb2008.admin.utils.jwt.JwtConstants;

import jakarta.annotation.Resource;

/**
 * M1 消息总线集成测试（校验链九分支 + 双频控 + 状态机 + 管理端点）
 * <p>
 * 独立内存 H2（MODE=MySQL），DB 全部 @Transactional 回滚；
 * bus.dispatch.enabled=false 关闭 dispatcher 后台线程（Redis 为 mock，投递路径不在本测试覆盖）。
 * FakeRedisConfig 显式列入 classes：dev profile 排除了 Redis 自动配置，嵌套 @TestConfiguration
 * 在 classes= 指定主配置时未被自动拾取，频控 ObjectProvider 解析不到模板会静默降级放行（t12/t13 失根因）。
 * <p>
 * 覆盖映射（任务书 §4 自测要求）：
 * 无 key 401 / scope 不足 403 / request 422 / 未知 type 422 / 分钟频控 429 / 日频控 429 /
 * sessionKey 非 bus-* 422 / sessionKey 越界字符 422 / model 越白名单 422 / payload 超 8KB 422 /
 * to 不存在 422 / body 伪造 fromAgentId 被忽略（落表 from=key 归属）/ 正常创建 201 /
 * 管理端列表/详情/重投（status 0/3）/ 手动发消息走同一校验链。
 */
@SpringBootTest(classes = { WebApplication.class, BusM1ApiTest.FakeRedisConfig.class }, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcbus;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-bus",
		// 目标路由：toAgentId=1（测试内固定 id=1 的 alpha 行），models 白名单两个
		"bus.target.1=1|ws://127.0.0.1:1/mock-gw|test-bootstrap-secret|models=model-a,model-b",
		"bus.dispatch.enabled=false",
		// bus-watchdog-fuse：白名单显式 1（守卫放开用例 t17~t20 依赖）；扫描不后台跑
		"bus.trusted-agents=1",
		"bus.fuse.scan-interval-ms=-1" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class BusM1ApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Resource
	private AgentService agentService;

	@Resource
	private ApiKeyService apiKeyService;

	@Resource
	private AgentMessageMapper messageMapper;

	/** 频控计数（FakeRedis 按前缀分流），用例内可重置 */
	static final AtomicLong MINUTE_COUNTER = new AtomicLong(0);
	static final AtomicLong DAILY_COUNTER = new AtomicLong(0);

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent producer;
	private String producerKey;

	@BeforeEach
	void seed() {
		MINUTE_COUNTER.set(0);
		DAILY_COUNTER.set(0);
		// 固定 id=1 目标（与 bus.target.1 的 toAgentId 对齐）：事务内幂等 INSERT，回滚不污染
		jdbcTemplateInsertTarget();
		String tag = "t" + SEQ.incrementAndGet();
		producer = agentService.createAgent("busp_" + tag, "总线生产者" + tag, null, "测试生产者", 1, 1L);
		producerKey = apiKeyService.generateKey(producer.getId(), List.of("bus:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
	}

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	private void jdbcTemplateInsertTarget() {
		jdbcTemplate.execute("DELETE FROM t_agent WHERE id = 1");
		jdbcTemplate.execute("INSERT INTO t_agent (id, name, display_name, description, status, created_by, "
				+ "create_time, update_time, is_deleted) VALUES (1, 'alpha', 'Alpha', '总线目标', 1, 1, "
				+ "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
	}

	@AfterEach
	void cleanRateCounters() {
		MINUTE_COUNTER.set(0);
		DAILY_COUNTER.set(0);
	}

	private String body(long toAgentId, String type, String topic, String title, Object payload,
			String sessionKey, String model) throws Exception {
		Map<String, Object> m = new java.util.HashMap<>();
		m.put("toAgentId", toAgentId);
		m.put("type", type);
		m.put("topic", topic);
		m.put("title", title);
		if (payload != null) {
			m.put("payload", payload);
		}
		if (sessionKey != null) {
			m.put("sessionKey", sessionKey);
		}
		if (model != null) {
			m.put("model", model);
		}
		return objectMapper.writeValueAsString(m);
	}

	private MvcResult postMessage(String key, String content) throws Exception {
		return mockMvc.perform(post("/api/agent/bus/messages").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(content))
				.andReturn();
	}

	private AgentMessage findByMessageId(String messageId) {
		return messageMapper.selectOne(
				com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentMessage>lambdaQuery()
						.eq(AgentMessage::getMessageId, messageId));
	}

	// ===================================================================
	// 鉴权与 scope
	// ===================================================================

	@Test
	@DisplayName("T_bus_01 无 key → 401")
	void t01_noKeyUnauthorized() throws Exception {
		mockMvc.perform(post("/api/agent/bus/messages")
				.contentType(MediaType.APPLICATION_JSON).content(body(1, "notify", "demo", "标题", null, null, null)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("T_bus_02 scope 不足（无 bus:write）→ 403")
	void t02_scopeDenied() throws Exception {
		String weakKey = apiKeyService.generateKey(producer.getId(), List.of("audit:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		mockMvc.perform(post("/api/agent/bus/messages").header("X-API-Key", weakKey)
				.contentType(MediaType.APPLICATION_JSON).content(body(1, "notify", "demo", "标题", null, null, null)))
				.andExpect(status().isForbidden());
	}

	// ===================================================================
	// 校验链分支
	// ===================================================================

	@Test
	@DisplayName("T_bus_03 正常创建 → 201 + messageId + 表 status=0/from=key 归属")
	void t03_createOk() throws Exception {
		MvcResult result = postMessage(producerKey, body(1, "notify", "demo-topic", "正常消息",
				Map.of("k", "v"), "bus-reuse-session", null));
		assertEquals(201, result.getResponse().getStatus(), "应为 201");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		Map<?, ?> data = (Map<?, ?>) resp.get("data");
		String messageId = (String) data.get("messageId");
		assertNotNull(messageId, "应返回 messageId");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row, "消息应落表");
		assertEquals(0, row.getStatus(), "初始 status=0");
		assertEquals(producer.getId(), row.getFromAgentId(), "from 应为 key 归属 agent");
		assertEquals("bus-reuse-session", row.getSessionKey(), "sessionKey 应透传");
	}

	@Test
	@DisplayName("T_bus_04 type=request → 422（总线只告知不指挥）")
	void t04_requestType422() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "request", "cmd", "派活", null, null, null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_05 未知 type → 422")
	void t05_unknownType422() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "foo", "topic", "标题", null, null, null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_06 sessionKey 非 bus-* 前缀 → 422（禁指定收件人既有会话）")
	void t06_sessionKeyNotBus() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null, "chat-abc", null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_07 sessionKey 越界字符（空格/中文/超长）→ 422")
	void t07_sessionKeyBadChars() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null, "bus-a b", null))
				.getResponse().getStatus());
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null, "bus-中文", null))
				.getResponse().getStatus());
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null, "bus-" + "a".repeat(60), null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_08 model 越白名单 → 422；白名单内 → 201")
	void t08_modelWhitelist() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null, null, "other-model"))
				.getResponse().getStatus(), "越白名单应 422");
		assertEquals(201, postMessage(producerKey, body(1, "notify", "topic", "标题", null, null, "model-b"))
				.getResponse().getStatus(), "白名单内应 201");
	}

	@Test
	@DisplayName("T_bus_09 payload 超 8KB → 422")
	void t09_payloadTooLarge() throws Exception {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < 8193) {
			sb.append("x");
		}
		Map<String, Object> payload = Map.of("blob", sb.toString());
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", payload, null, null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_10 目标不存在/未启用 → 422")
	void t10_targetMissing() throws Exception {
		assertEquals(422, postMessage(producerKey, body(99999, "notify", "topic", "标题", null, null, null))
				.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_bus_11 body 伪造 fromAgentId 被忽略（落表 from=key 归属）")
	void t11_fromForgedIgnored() throws Exception {
		String content = objectMapper.writeValueAsString(Map.of(
				"toAgentId", 1,
				"type", "notify",
				"topic", "forge",
				"title", "伪造 from",
				"fromAgentId", 999));
		MvcResult result = postMessage(producerKey, content);
		assertEquals(201, result.getResponse().getStatus());
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String messageId = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row);
		assertEquals(producer.getId(), row.getFromAgentId(), "from 应为 key 归属，body 伪造无效");
		assertNotEquals(999L, row.getFromAgentId());
	}

	// ===================================================================
	// 频控双限
	// ===================================================================

	@Test
	@DisplayName("T_bus_12 分钟频控：限值连发第 N+1 条 429")
	void t12_minuteRateLimit() throws Exception {
		int limit = BusRateLimiter.MINUTE_LIMIT;
		for (int i = 1; i <= limit; i++) {
			assertEquals(201, postMessage(producerKey, body(1, "notify", "rate", "第" + i + "条", null, null, null))
					.getResponse().getStatus(), "前 " + limit + " 条应放行");
		}
		assertEquals(429, postMessage(producerKey, body(1, "notify", "rate", "第" + (limit + 1) + "条", null, null, null))
				.getResponse().getStatus(), "第 " + (limit + 1) + " 条应 429");
	}

	@Test
	@DisplayName("T_bus_13 日频控：daily 计数达日限后 → 429")
	void t13_dailyRateLimit() throws Exception {
		DAILY_COUNTER.set(BusRateLimiter.DAILY_LIMIT);
		assertEquals(429, postMessage(producerKey, body(1, "notify", "rate", "日限触发", null, null, null))
				.getResponse().getStatus());
	}

	// ===================================================================
	// 管理端点（JWT，super_admin 全权限）
	// ===================================================================

	private String adminToken() throws Exception {
		MvcResult login = mockMvc.perform(post("/system/user/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"123456\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return login.getResponse().getHeader(JwtConstants.HEADER);
	}

	@Test
	@DisplayName("T_bus_14 管理端列表/详情/统计可用")
	void t14_adminListAndDetail() throws Exception {
		MvcResult created = postMessage(producerKey, body(1, "alert", "alpha-exception", "UP≤2 冲突挂起 11h",
				Map.of("detail", "dev UP=1"), "bus-alpha-20261001", null));
		assertEquals(201, created.getResponse().getStatus(), "alert 消息应创建成功");
		Map<?, ?> resp = objectMapper.readValue(created.getResponse().getContentAsString(), Map.class);
		String mid = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		assertNotNull(mid);
		AgentMessage row = findByMessageId(mid);
		assertNotNull(row);

		String token = adminToken();
		// 列表（筛选 type=alert）
		MvcResult listResult = mockMvc.perform(get("/system/bus/messages")
				.header(JwtConstants.HEADER, token)
				.param("type", "alert").param("page", "1").param("size", "10"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> listBody = objectMapper.readValue(listResult.getResponse().getContentAsString(), Map.class);
		assertEquals(Boolean.TRUE, listBody.get("success"));
		Map<?, ?> listData = (Map<?, ?>) listBody.get("data");
		List<?> rows = (List<?>) listData.get("list");
		assertNotNull(rows);
		assertTrue(rows.size() >= 1, "列表应含刚才的消息");
		Map<?, ?> firstRow = (Map<?, ?>) rows.get(0);
		assertEquals("Alpha", firstRow.get("toAgentName"), "应解析目标展示名");

		// 详情
		MvcResult detailResult = mockMvc.perform(get("/system/bus/messages/" + row.getId())
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> detailBody = objectMapper.readValue(detailResult.getResponse().getContentAsString(), Map.class);
		Map<?, ?> detail = (Map<?, ?>) detailBody.get("data");
		assertEquals(mid, detail.get("messageId"));
		assertEquals("bus-alpha-20261001", detail.get("sessionKey"));

		// 统计
		MvcResult statsResult = mockMvc.perform(get("/system/bus/stats")
				.header(JwtConstants.HEADER, token).param("days", "7"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> statsBody = objectMapper.readValue(statsResult.getResponse().getContentAsString(), Map.class);
		Map<?, ?> stats = (Map<?, ?>) statsBody.get("data");
		assertNotNull(stats.get("todayTotal"));
		assertTrue(((Number) stats.get("todayTotal")).longValue() >= 1, "今日进线应含刚才的消息");
	}

	@Test
	@DisplayName("T_bus_15 管理端手动发消息走同一校验链（request 422 / 正常 201 from=所选）")
	void t15_adminManualSend() throws Exception {
		String token = adminToken();
		// request → 422（同一校验链）
		mockMvc.perform(post("/system/bus/messages").header(JwtConstants.HEADER, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"fromAgentId", producer.getId(), "toAgentId", 1,
						"type", "request", "topic", "cmd", "title", "派活"))))
				.andExpect(status().isUnprocessableEntity());
		// model 越白名单 → 422
		mockMvc.perform(post("/system/bus/messages").header(JwtConstants.HEADER, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"fromAgentId", producer.getId(), "toAgentId", 1,
						"type", "notify", "topic", "manual", "title", "手动演练",
						"model", "other-model"))))
				.andExpect(status().isUnprocessableEntity());
		// 正常 → 201，落表 from=所选 fromAgentId
		MvcResult result = mockMvc.perform(post("/system/bus/messages").header(JwtConstants.HEADER, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
						"fromAgentId", producer.getId(), "toAgentId", 1,
						"type", "alert", "topic", "manual-drill", "title", "手动发消息演练"))))
				.andExpect(status().isCreated())
				.andReturn();
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String mid = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		AgentMessage row = findByMessageId(mid);
		assertNotNull(row);
		assertEquals(producer.getId(), row.getFromAgentId(), "from 应为所选的模拟生产者");
		assertEquals("alert", row.getMsgType());
	}

	@Test
	@DisplayName("T_bus_16 重投：status=3 → 200 置 0 清零 retry；status=1 → 400")
	void t16_retryStateMachine() throws Exception {
		// 造失败行
		AgentMessage failed = new AgentMessage();
		failed.setMessageId(java.util.UUID.randomUUID().toString());
		failed.setFromAgentId(producer.getId());
		failed.setToAgentId(1L);
		failed.setMsgType("notify");
		failed.setTopic("retry");
		failed.setTitle("失败行");
		failed.setStatus(BusMessageService.STATUS_FAILED);
		failed.setRetryCount(5);
		failed.setLastError("connect failed: mocked");
		messageMapper.insert(failed);
		// 造已送达行
		AgentMessage delivered = new AgentMessage();
		delivered.setMessageId(java.util.UUID.randomUUID().toString());
		delivered.setFromAgentId(producer.getId());
		delivered.setToAgentId(1L);
		delivered.setMsgType("notify");
		delivered.setTopic("retry");
		delivered.setTitle("已送达行");
		delivered.setStatus(BusMessageService.STATUS_DELIVERED);
		delivered.setDeliveredAt(new Date());
		messageMapper.insert(delivered);

		String token = adminToken();
		// status=1 → 400
		mockMvc.perform(put("/system/bus/messages/" + delivered.getId() + "/retry")
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isBadRequest());
		// status=3 → 200 且重置
		mockMvc.perform(put("/system/bus/messages/" + failed.getId() + "/retry")
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isOk());
		AgentMessage after = messageMapper.selectById(failed.getId());
		assertEquals(0, after.getStatus(), "重投后应置待投递");
		assertEquals(0, after.getRetryCount(), "重试计数应清零");
		assertNotNull(after.getLastError(), "last_error 保留（管理端可见上次失败原因）");
	}

	// ===================================================================
	// bus-watchdog-fuse：sessionKey 守卫放开（②）+ expectReplyMinutes（①）
	// ===================================================================

	/** 白名单 agent（id=1，与 bus.trusted-agents=1 对齐）的 key */
	private String trustedKey() {
		return apiKeyService.generateKey(1L, List.of("bus:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
	}

	@Test
	@DisplayName("T_bus_17 白名单 agent 携 websocket: 会话落点 → 201 且落表透传（守卫放开）")
	void t17_trustedWebsocketAllowed() throws Exception {
		MvcResult result = postMessage(trustedKey(), body(1, "notify", "deliver-fuse-demo", "交付回投",
				Map.of("deliverBack", true), "websocket:3f2b9a1c-1111-2222-3333-444455556666", null));
		assertEquals(201, result.getResponse().getStatus(), "白名单 agent 的 websocket: 落点应放行");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String messageId = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row);
		assertEquals("websocket:3f2b9a1c-1111-2222-3333-444455556666", row.getSessionKey(), "落点应透传");
		assertEquals(1L, row.getFromAgentId());
	}

	@Test
	@DisplayName("T_bus_18 白名单 agent 非 bus-*/websocket: 落点 → 422（放开仅限 websocket: 前缀）")
	void t18_trustedOtherPrefixRejected() throws Exception {
		assertEquals(422, postMessage(trustedKey(), body(1, "notify", "topic", "标题", null, "qq:12345", null))
				.getResponse().getStatus(), "白名单仅放开 websocket:，其他前缀仍 422");
		assertEquals(422, postMessage(trustedKey(), body(1, "notify", "topic", "标题", null, "websocket:", null))
				.getResponse().getStatus(), "websocket: 空标识应 422");
		assertEquals(422, postMessage(trustedKey(), body(1, "notify", "topic", "标题", null,
				"websocket:bad space", null)).getResponse().getStatus(), "websocket: 非法字符应 422");
	}

	@Test
	@DisplayName("T_bus_19 非白名单 agent 携 websocket: → 422（守卫对非白名单不放开）")
	void t19_untrustedWebsocketRejected() throws Exception {
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null,
				"websocket:3f2b9a1c-1111-2222-3333-444455556666", null)).getResponse().getStatus(),
				"非白名单 agent 的 websocket: 落点应维持 bus-* 守卫拒绝");
	}

	// ===================================================================
	// v4.37 bus-sessionkey-fix：定向落点放宽（裸 uuid / api:<uuid>）
	// ===================================================================

	@Test
	@DisplayName("T_bus_22 白名单 agent 裸 uuid 落点 → 201（v4.37 放开，不再 422）")
	void t22_trustedBareUuidAllowed() throws Exception {
		MvcResult result = postMessage(trustedKey(), body(1, "notify", "topic", "标题", null,
				"3f2b9a1c-1111-2222-3333-444455556666", null));
		assertEquals(201, result.getResponse().getStatus(), "白名单 agent 的裸 uuid 落点应放行（v4.37）");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String messageId = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row);
		assertEquals("3f2b9a1c-1111-2222-3333-444455556666", row.getSessionKey(), "裸 uuid 应落表透传");
	}

	@Test
	@DisplayName("T_bus_23 白名单 api:<uuid> → 201；非白名单裸 uuid → 422；websocket: 非 uuid 标识收紧 422")
	void t23_apiPrefixAllowedAndGuardKept() throws Exception {
		MvcResult ok = postMessage(trustedKey(), body(1, "notify", "topic", "标题", null,
				"api:3f2b9a1c-1111-2222-3333-444455556666", null));
		assertEquals(201, ok.getResponse().getStatus(), "api:<uuid> 落点应放行（v4.37）");
		assertEquals(422, postMessage(producerKey, body(1, "notify", "topic", "标题", null,
				"3f2b9a1c-1111-2222-3333-444455556666", null)).getResponse().getStatus(),
				"非白名单 agent 的裸 uuid 应维持守卫拒绝（守卫不放开）");
		assertEquals(422, postMessage(trustedKey(), body(1, "notify", "topic", "标题", null,
				"websocket:abc123", null)).getResponse().getStatus(),
				"websocket: 非 uuid 标识应收紧 422（v4.37 口径：前缀后须 uuid）");
	}

	// ===================================================================
	// busfix-1007：顶层 content 兼容映射 + 空 payload 入队告警
	// ===================================================================

	private String contentBody(long toAgentId, String type, String topic, String title,
			Object payload, String content) throws Exception {
		Map<String, Object> m = new java.util.HashMap<>();
		m.put("toAgentId", toAgentId);
		m.put("type", type);
		m.put("topic", topic);
		m.put("title", title);
		if (payload != null) {
			m.put("payload", payload);
		}
		if (content != null) {
			m.put("content", content);
		}
		return objectMapper.writeValueAsString(m);
	}

	@Test
	@DisplayName("T_bus_24 顶层 content 无 payload → 201 自动映射 payload={\"content\":…} 且无 warning")
	void t24_topLevelContentMapped() throws Exception {
		MvcResult result = postMessage(producerKey,
				contentBody(1, "notify", "bp-en-sample", "交付回执", null, "回执正文：MR 已合并"));
		assertEquals(201, result.getResponse().getStatus(), "顶层 content 应兼容放行");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		Map<?, ?> data = (Map<?, ?>) resp.get("data");
		String messageId = (String) data.get("messageId");
		assertNotNull(messageId);
		assertFalse(data.containsKey("warning"), "映射成功不应有 warning");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row);
		Map<?, ?> payloadMap = objectMapper.readValue(row.getPayload(), Map.class);
		assertEquals("回执正文：MR 已合并", payloadMap.get("content"), "payload 应为 {\"content\": 值}");
	}

	@Test
	@DisplayName("T_bus_25 notify 无 payload 无 content → 201 照常入队 + data.warning")
	void t25_notifyEmptyPayloadWarns() throws Exception {
		MvcResult result = postMessage(producerKey, contentBody(1, "notify", "t", "标题", null, null));
		assertEquals(201, result.getResponse().getStatus(), "payload 空照常入队不阻断");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		Map<?, ?> data = (Map<?, ?>) resp.get("data");
		assertEquals(BusMessageService.PAYLOAD_EMPTY_WARNING, data.get("warning"), "应有空 payload 告警");
		assertNotNull(data.get("messageId"), "warning 不影响返回 messageId");
	}

	@Test
	@DisplayName("T_bus_26 payload 优先于 content；payload 无 content 键（notify）仍告警")
	void t26_contentPriorityAndNoWarn() throws Exception {
		// payload.content 有值 → 无 warning，顶层 content 兼容字段被忽略（不合并）
		MvcResult ok = postMessage(producerKey,
				contentBody(1, "notify", "t", "标题", Map.of("content", "正文本体"), "顶层content应被忽略"));
		assertEquals(201, ok.getResponse().getStatus());
		Map<?, ?> okResp = objectMapper.readValue(ok.getResponse().getContentAsString(), Map.class);
		assertFalse(((Map<?, ?>) okResp.get("data")).containsKey("warning"), "payload.content 有值不应告警");
		// notify + payload 有值但无 content 键 → 按口径告警（正文只认 content）
		MvcResult other = postMessage(producerKey,
				contentBody(1, "notify", "t", "标题", Map.of("foo", "bar"), null));
		assertEquals(201, other.getResponse().getStatus());
		Map<?, ?> otherResp = objectMapper.readValue(other.getResponse().getContentAsString(), Map.class);
		assertEquals(BusMessageService.PAYLOAD_EMPTY_WARNING,
				((Map<?, ?>) otherResp.get("data")).get("warning"), "payload 无 content 键应告警");
	}

	@Test
	@DisplayName("T_bus_27 alert 无 payload → 201 无 warning（alert 标题即正文不提示）")
	void t27_alertNoWarn() throws Exception {
		MvcResult result = postMessage(producerKey, contentBody(1, "alert", "alpha-exception", "告警", null, null));
		assertEquals(201, result.getResponse().getStatus());
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		assertFalse(((Map<?, ?>) resp.get("data")).containsKey("warning"), "alert 不应有 warning");
	}

	@Test
	@DisplayName("T_bus_20 expectReplyMinutes：-1/1441 → 422；0（无需回音）与 1 → 201 且列落库")
	void t20_expectReplyMinutesValidation() throws Exception {
		Map<String, Object> over = new java.util.HashMap<>();
		over.put("toAgentId", 1);
		over.put("type", "notify");
		over.put("topic", "fuse-t");
		over.put("title", "超上限");
		over.put("expectReplyMinutes", 1441);
		assertEquals(422, postMessage(producerKey, objectMapper.writeValueAsString(over))
				.getResponse().getStatus(), "超上限应 422");
		Map<String, Object> negative = new java.util.HashMap<>(over);
		negative.put("title", "负数");
		negative.put("expectReplyMinutes", -1);
		assertEquals(422, postMessage(producerKey, objectMapper.writeValueAsString(negative))
				.getResponse().getStatus(), "负数应 422");
		Map<String, Object> zero = new java.util.HashMap<>(over);
		zero.put("title", "无需回音档");
		zero.put("expectReplyMinutes", 0);
		MvcResult zeroResult = postMessage(producerKey, objectMapper.writeValueAsString(zero));
		assertEquals(201, zeroResult.getResponse().getStatus(), "0（无需回音）应放行");
		Map<?, ?> zeroResp = objectMapper.readValue(zeroResult.getResponse().getContentAsString(), Map.class);
		AgentMessage zeroRow = findByMessageId(
				(String) ((Map<?, ?>) zeroResp.get("data")).get("messageId"));
		assertNotNull(zeroRow);
		assertEquals(0, zeroRow.getExpectReplyMinutes(), "0 应落库（无需回音档）");
		Map<String, Object> ok = new java.util.HashMap<>(over);
		ok.put("title", "边界内");
		ok.put("expectReplyMinutes", 1);
		MvcResult result = postMessage(producerKey, objectMapper.writeValueAsString(ok));
		assertEquals(201, result.getResponse().getStatus(), "边界内应 201");
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String messageId = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		AgentMessage row = findByMessageId(messageId);
		assertNotNull(row);
		assertEquals(1, row.getExpectReplyMinutes(), "expectReplyMinutes 应落库");
	}

	// ===================================================================
	// 频控 mock（按 key 前缀分流的假 Redis）
	// ===================================================================

	@TestConfiguration
	static class FakeRedisConfig {

		@Bean
		@Primary
		@SuppressWarnings({"unchecked", "rawtypes"})
		public StringRedisTemplate fakeStringRedisTemplate() {
			StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
			ValueOperations<String, String> valueOps = mock(ValueOperations.class);
			ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
			when(template.opsForValue()).thenReturn(valueOps);
			when(valueOps.increment(anyString())).thenAnswer(inv -> {
				String key = inv.getArgument(0);
				System.out.println("[FAKE-REDIS] increment " + key);
				if (key.contains("agent-bus:rate:daily:")) {
					return DAILY_COUNTER.incrementAndGet();
				}
				if (key.contains("agent-bus:rate:")) {
					return MINUTE_COUNTER.incrementAndGet();
				}
				// RateLimitFilter / 审计缓冲等其他 key：恒小值放行
				return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet() <= 60 ? 1L : 61L;
			});
			System.out.println("[FAKE-REDIS] template created stubbed=" + (valueOps != null));
			return template;
		}
	}
}
