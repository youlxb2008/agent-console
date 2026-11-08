package com.youlxb2008.admin.modules.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;

import jakarta.annotation.Resource;

/**
 * M1 门户包集成测试（/api/portal/bus/messages + /api/portal/bus/stats，匿名只读）
 * <p>
 * 独立内存 H2（MODE=MySQL），DB 全部 @Transactional 回滚；bus.dispatch.enabled=false 关闭
 * dispatcher 后台线程。FakeRedisConfig 显式列入 classes（与 BusM1ApiTest 同因：dev profile
 * 排除 Redis 自动配置，嵌套 @TestConfiguration 不被自动拾取）。
 * <p>
 * 覆盖映射（门户包任务书 §5 验收基准）：
 * 匿名 200 + BPage 形态 / 字段白名单（响应不含 payload/sessionKey/model/lastError/messageId）/
 * agent 名解析 / id 倒序 / size 上限 100 钳制 / from+to+type+status 筛选 / stats 四计数口径。
 */
@SpringBootTest(classes = { WebApplication.class, BusPortalApiTest.FakeRedisConfig.class }, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcbusportal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-bus-portal",
		"bus.target.1=1|ws://127.0.0.1:1/mock-gw|test-bootstrap-secret|models=model-a,model-b",
		"bus.dispatch.enabled=false",
		"bus.fuse.scan-interval-ms=-1" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class BusPortalApiTest {

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
		// 固定 id=1 目标（与 bus.target.1 对齐）：事务内幂等 INSERT，回滚不污染
		jdbcTemplate.execute("DELETE FROM t_agent WHERE id = 1");
		jdbcTemplate.execute("INSERT INTO t_agent (id, name, display_name, description, status, created_by, "
				+ "create_time, update_time, is_deleted) VALUES (1, 'alpha', 'Alpha', '总线目标', 1, 1, "
				+ "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
		String tag = "t" + SEQ.incrementAndGet();
		producer = agentService.createAgent("buspp_" + tag, "门户生产者" + tag, null, "测试生产者", 1, 1L);
		producerKey = apiKeyService.generateKey(producer.getId(), List.of("bus:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
	}

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@AfterEach
	void cleanRateCounters() {
		MINUTE_COUNTER.set(0);
		DAILY_COUNTER.set(0);
	}

	/** 经生产 API 造一条消息（from=key 归属），返回落表行 */
	private AgentMessage createMessage(String type, String topic, String title) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/agent/bus/messages").header("X-API-Key", producerKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(Map.of(
								"toAgentId", 1, "type", type, "topic", topic, "title", title))))
				.andExpect(status().isCreated()).andReturn();
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		String messageId = (String) ((Map<?, ?>) resp.get("data")).get("messageId");
		assertNotNull(messageId);
		return messageMapper.selectOne(
				com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentMessage>lambdaQuery()
						.eq(AgentMessage::getMessageId, messageId));
	}

	/** 直接插一行指定状态（stats 口径用；createTime=now 必落在「今日」窗内） */
	private AgentMessage insertRow(int status) {
		AgentMessage row = new AgentMessage();
		row.setMessageId(java.util.UUID.randomUUID().toString());
		row.setFromAgentId(producer.getId());
		row.setToAgentId(1L);
		row.setMsgType("notify");
		row.setTopic("stats");
		row.setTitle("统计行 status=" + status);
		row.setStatus(status);
		row.setRetryCount(0);
		row.setCreateTime(new Date());
		messageMapper.insert(row);
		return row;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getBody(String url) throws Exception {
		MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		assertEquals(Boolean.TRUE, resp.get("success"), "应 success=true");
		return (Map<String, Object>) resp.get("data");
	}

	/**
	 * 带参请求（含中文）：走 .param() 直设参数。
	 * MockMvc 手工 URLEncoder 拼 query 会按 ISO-8859-1 解码致查空（前置项目持久坑同款），
	 * keyword 中文双路命中测试必须用本助手。
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> getBodyQ(String path, String... kv) throws Exception {
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b = get(path);
		for (int i = 0; i + 1 < kv.length; i += 2) {
			b.param(kv[i], kv[i + 1]);
		}
		MvcResult result = mockMvc.perform(b).andExpect(status().isOk()).andReturn();
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		assertEquals(Boolean.TRUE, resp.get("success"), "应 success=true");
		return (Map<String, Object>) resp.get("data");
	}

	@Test
	@DisplayName("T_busp_01 匿名 200 + BPage 形态 + 字段白名单（无 payload/sessionKey/model/lastError/messageId）")
	void t01_anonymousPageWhitelist() throws Exception {
		createMessage("notify", "portal-demo", "门户白名单校验");
		Map<String, Object> data = getBody("/api/portal/bus/messages?page=1&size=20");
		List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) data.get("list");
		assertNotNull(rows, "list 应存在");
		assertTrue(((Number) data.get("count")).longValue() >= 1, "count 应 >= 1");
		assertTrue(rows.size() >= 1, "应有数据行");
		Map<String, Object> first = rows.get(0);
		// 白名单字段存在
		for (String key : new String[] {"id", "fromAgentId", "fromAgentName", "toAgentId", "toAgentName",
				"msgType", "topic", "title", "status", "retryCount", "createTime"}) {
			assertTrue(first.containsKey(key), "应含白名单字段 " + key);
		}
		// 内部字段绝不出现（匿名公开域防泄漏；grep 断言整行 JSON）
		String rowJson = objectMapper.writeValueAsString(rows);
		for (String banned : new String[] {"payload", "sessionKey", "session_key", "model",
				"lastError", "last_error", "messageId", "message_id", "updateTime"}) {
			assertFalse(rowJson.contains(banned), "响应不得出现内部字段 " + banned);
		}
		// agent 名解析（displayName 优先）：to=1 显示名「Alpha」
		assertEquals("Alpha", first.get("toAgentName"), "应解析目标展示名");
		assertNotNull(first.get("fromAgentName"), "应解析来源展示名");
	}

	@Test
	@DisplayName("T_busp_02 id 倒序 + size 上限 100 钳制（size=500 → pageSize=100）")
	void t02_orderAndSizeClamp() throws Exception {
		AgentMessage a = createMessage("notify", "order", "第一条");
		AgentMessage b = createMessage("notify", "order", "第二条");
		AgentMessage c = createMessage("alert", "order", "第三条");
		Map<String, Object> data = getBody("/api/portal/bus/messages?page=1&size=20");
		List<Map<String, Object>> rows = (List<Map<String, Object>>) (List<?>) data.get("list");
		assertEquals(c.getId(), ((Number) rows.get(0).get("id")).longValue(), "最新一条应在首位");
		assertEquals(b.getId(), ((Number) rows.get(1).get("id")).longValue());
		assertEquals(a.getId(), ((Number) rows.get(2).get("id")).longValue());
		// size 钳制
		Map<String, Object> clamped = getBody("/api/portal/bus/messages?page=1&size=500");
		assertEquals(100, ((Number) clamped.get("pageSize")).intValue(), "size 应钳制为 100");
	}

	@Test
	@DisplayName("T_busp_03 筛选：type / status / from / to 精确匹配")
	void t03_filters() throws Exception {
		AgentMessage notify = createMessage("notify", "filter", "通知消息");
		createMessage("alert", "filter", "告警消息");
		AgentMessage failed = insertRow(BusMessageService.STATUS_FAILED);

		// type=alert → 只剩 alert 一条
		Map<String, Object> byType = getBody("/api/portal/bus/messages?type=alert");
		List<Map<String, Object>> alertRows = (List<Map<String, Object>>) (List<?>) byType.get("list");
		assertEquals(1, alertRows.size(), "type=alert 应只命中 1 条");
		assertEquals("alert", alertRows.get(0).get("msgType"));

		// status=3 → 命中直插的失败行
		Map<String, Object> byStatus = getBody("/api/portal/bus/messages?status=3");
		List<Map<String, Object>> failedRows = (List<Map<String, Object>>) (List<?>) byStatus.get("list");
		assertEquals(1, failedRows.size(), "status=3 应只命中 1 条");
		assertEquals(failed.getId(), ((Number) failedRows.get(0).get("id")).longValue());

		// from=生产者 → notify + alert 两条 API 消息（直插行 from 同为生产者，共 3 条）
		Map<String, Object> byFrom = getBody("/api/portal/bus/messages?from=" + producer.getId());
		assertEquals(3, ((Number) byFrom.get("count")).intValue(), "from 筛选应命中 3 条");

		// to=1（全部）与 to=99999（空）
		Map<String, Object> byTo = getBody("/api/portal/bus/messages?to=1");
		assertEquals(3, ((Number) byTo.get("count")).intValue(), "to=1 应命中全部 3 条");
		Map<String, Object> byToMiss = getBody("/api/portal/bus/messages?to=99999");
		assertEquals(0, ((Number) byToMiss.get("count")).intValue(), "不存在的 to 应空");
		assertNotNull(notify);
	}

	@Test
	@DisplayName("T_busp_04 stats 四计数口径（今日进线/已投递/待投/失败=其余状态）")
	void t04_stats() throws Exception {
		insertRow(BusMessageService.STATUS_PENDING);
		insertRow(BusMessageService.STATUS_DELIVERED);
		insertRow(BusMessageService.STATUS_FAILED);
		insertRow(BusMessageService.STATUS_READ);
		Map<String, Object> stats = getBody("/api/portal/bus/stats");
		assertEquals(4, ((Number) stats.get("todayTotal")).intValue(), "今日进线应为 4");
		assertEquals(1, ((Number) stats.get("delivered")).intValue(), "已投递应为 1");
		assertEquals(1, ((Number) stats.get("pending")).intValue(), "待投应为 1");
		assertEquals(2, ((Number) stats.get("failed")).intValue(), "失败应含 status=2/3 共 2 条");
	}

	@Test
	@DisplayName("T_busp_05 bus-ui-v2 筛选：topic 精确匹配")
	void t05_filterTopic() throws Exception {
		createMessage("notify", "alpha", "主题筛选一");
		createMessage("notify", "alpha", "主题筛选二");
		createMessage("alert", "beta", "主题筛选三");
		Map<?, ?> hit = getBody("/api/portal/bus/messages?topic=alpha");
		assertEquals(2, ((Number) hit.get("count")).intValue(), "topic=alpha 应 2 条");
		Map<?, ?> none = getBody("/api/portal/bus/messages?topic=gamma");
		assertEquals(0, ((Number) none.get("count")).intValue(), "不存在的 topic 应 0 条");
	}

	@Test
	@DisplayName("T_busp_06 bus-ui-v2 筛选：days 时间窗（31 天前旧行 days=7 排除、缺省不过滤）")
	void t06_filterDays() throws Exception {
		createMessage("notify", "days-demo", "新鲜行");
		AgentMessage stale = createMessage("notify", "days-demo", "31 天前的旧行");
		stale.setCreateTime(new Date(System.currentTimeMillis() - 31L * 24 * 3600 * 1000));
		messageMapper.updateById(stale);
		Map<?, ?> week = getBody("/api/portal/bus/messages?days=7");
		assertEquals(1, ((Number) week.get("count")).intValue(), "days=7 应只含新鲜行");
		Map<?, ?> all = getBody("/api/portal/bus/messages");
		assertEquals(2, ((Number) all.get("count")).intValue(), "缺省 days 不过滤");
	}

	@Test
	@DisplayName("T_busp_07 bus-ui-v2 筛选：keyword 双路（title/topic LIKE）+ 通配符转义")
	void t07_filterKeyword() throws Exception {
		createMessage("notify", "release-note", "发布 v2 上线");
		createMessage("notify", "release-note", "无关标题");
		createMessage("notify", "other", "另一个");
		Map<?, ?> byTitle = getBodyQ("/api/portal/bus/messages", "keyword", "发布");
		assertEquals(1, ((Number) byTitle.get("count")).intValue(), "title 命中");
		Map<?, ?> byTopic = getBodyQ("/api/portal/bus/messages", "keyword", "release");
		assertEquals(2, ((Number) byTopic.get("count")).intValue(), "topic 命中两条");
		Map<?, ?> literal = getBodyQ("/api/portal/bus/messages", "keyword", "100%");
		assertEquals(0, ((Number) literal.get("count")).intValue(), "% 应被转义为字面量不误命中");
	}

	@Test
	@DisplayName("T_busp_08 bus-ui-v2：channel 筛选 + 行 channel 透出（历史 NULL 行原样 null）")
	void t08_filterChannel() throws Exception {
		AgentMessage api = createMessage("notify", "ch-demo", "api 通道行");
		api.setChannel(BusRoute.CHANNEL_API);
		messageMapper.updateById(api);
		AgentMessage ws = createMessage("notify", "ch-demo", "ws 通道行");
		ws.setChannel(BusRoute.CHANNEL_WS);
		messageMapper.updateById(ws);
		createMessage("notify", "ch-demo", "历史 NULL 行");
		Map<?, ?> byApi = getBody("/api/portal/bus/messages?channel=api");
		assertEquals(1, ((Number) byApi.get("count")).intValue(), "channel=api 应 1 条");
		Map<?, ?> page = getBody("/api/portal/bus/messages?topic=ch-demo&size=10");
		Map<String, Object> apiRow = null;
		Map<String, Object> legacyRow = null;
		for (Object o : (List<?>) page.get("list")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> row = (Map<String, Object>) o;
			if ("api 通道行".equals(row.get("title"))) {
				apiRow = row;
			}
			if ("历史 NULL 行".equals(row.get("title"))) {
				legacyRow = row;
			}
		}
		assertNotNull(apiRow, "应有 api 通道行");
		assertEquals("api", apiRow.get("channel"), "行 channel 应透出");
		assertNotNull(legacyRow, "应有历史 NULL 行");
		assertNull(legacyRow.get("channel"), "历史行 channel=null 前端显「—」");
	}

	@Test
	@DisplayName("T_busp_09 bus-ui-v2 stats：hotTopics top6 + topics 全量（count 降序、同数按 topic 升序）")
	void t09_statsTopics() throws Exception {
		createMessage("notify", "hot-alpha", "a1");
		createMessage("notify", "hot-alpha", "a2");
		createMessage("notify", "hot-alpha", "a3");
		createMessage("notify", "hot-beta", "b1");
		Map<?, ?> stats = getBody("/api/portal/bus/stats");
		List<?> topics = (List<?>) stats.get("topics");
		Map<?, ?> first = (Map<?, ?>) topics.get(0);
		assertEquals("hot-alpha", first.get("topic"), "count 降序首位");
		assertEquals(3, ((Number) first.get("count")).intValue());
		assertEquals("hot-beta", ((Map<?, ?>) topics.get(1)).get("topic"));
		List<?> hot = (List<?>) stats.get("hotTopics");
		assertEquals(2, hot.size(), "不足 6 全量返回");
	}

	// ===================================================================
	// 频控 mock（按 key 前缀分流的假 Redis；生产 API 造数走同一校验链）
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
				if (key.contains("agent-bus:rate:daily:")) {
					return DAILY_COUNTER.incrementAndGet();
				}
				if (key.contains("agent-bus:rate:")) {
					return MINUTE_COUNTER.incrementAndGet();
				}
				// RateLimitFilter / 审计缓冲等其他 key：恒小值放行
				return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet() <= 60 ? 1L : 61L;
			});
			return template;
		}
	}
}
