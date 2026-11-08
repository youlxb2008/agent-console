package com.youlxb2008.admin.modules.bus;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.mapper.BusRouteMapper;
import com.youlxb2008.admin.utils.jwt.JwtConstants;

import jakarta.annotation.Resource;

/**
 * bus-route-channel 管理端集成测试（投递路由矩阵 / 单方向 upsert / 批量保存 / 校验链）
 * <p>
 * 独立内存 H2（MODE=MySQL），DB 全部 @Transactional 回滚；bus.dispatch.enabled=false
 * 关闭 dispatcher 后台线程（投递分叉逻辑在 BusRouteChannelTest 覆盖）。
 * 管理端 JWT=admin/123456 登录取响应头（super_admin 全权限放行，同 BusM1ApiTest）。
 */
@SpringBootTest(classes = { WebApplication.class }, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcbusroute;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-busroute",
		"bus.dispatch.enabled=false",
		"bus.fuse.scan-interval-ms=-1",
		// delivery-targets 解析源（bus-ui-v2 t10）：agent1 配 ws、agent2 配 api，agent3 两项均缺 = 无实例
		"bus.target.1=1|ws://127.0.0.1:1/mock-gw|test-bootstrap-secret|models=*",
		"bus.api.target.2=2|http://127.0.0.1:1/mock-api|k2" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class BusRouteApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Resource
	private BusRouteMapper routeMapper;

	@Resource
	private AgentMessageMapper messageMapper;

	@BeforeEach
	void seedAgents() {
		// 三个固定 agent（事务内幂等 INSERT，回滚不污染）：方向矩阵 3*2=6 行
		jdbcTemplate.execute("DELETE FROM t_agent WHERE id IN (1, 2, 3)");
		String[] rows = {
				"(1, 'alpha', 'Alpha', '总线目标', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
				"(2, 'bravo', 'Bravo', 'worker', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
				"(3, 'gamma', 'Gamma', 'worker', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)"
		};
		for (String row : rows) {
			jdbcTemplate.execute("INSERT INTO t_agent (id, name, display_name, description, status, "
					+ "created_by, create_time, update_time, is_deleted) VALUES " + row);
		}
	}

	private String adminToken() throws Exception {
		MvcResult login = mockMvc.perform(post("/system/user/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"admin\",\"password\":\"123456\"}"))
				.andReturn();
		return login.getResponse().getHeader(JwtConstants.HEADER);
	}

	private MvcResult putRoute(String token, String body) throws Exception {
		return mockMvc.perform(put("/system/bus/routes").header(JwtConstants.HEADER, token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
	}

	private JsonNode listRoutes(String token) throws Exception {
		MvcResult result = mockMvc.perform(get("/system/bus/routes")
						.header(JwtConstants.HEADER, token))
				.andReturn();
		assertEquals(200, result.getResponse().getStatus());
		Map<?, ?> resp = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
		return objectMapper.valueToTree(resp).path("data");
	}

	@Test
	@DisplayName("T_rt_api_01 无 token → 401")
	void t01_noTokenUnauthorized() throws Exception {
		mockMvc.perform(get("/system/bus/routes")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("T_rt_api_02 矩阵缺省：3 agent 两两方向含自投 9 行全 ws，from/to 升序，带展示名（bus-ui-v2 含自投）")
	void t02_matrixDefaultAllWs() throws Exception {
		String token = adminToken();
		JsonNode data = listRoutes(token);
		assertEquals(9, data.size(), "3 个 agent 两两方向含自投应 9 行");
		assertEquals(1, data.get(0).path("fromAgentId").asLong());
		assertEquals(1, data.get(0).path("toAgentId").asLong(), "升序首行 = 1→1 自投行");
		assertEquals(1, data.get(1).path("fromAgentId").asLong());
		assertEquals(2, data.get(1).path("toAgentId").asLong());
		for (JsonNode row : data) {
			assertEquals("ws", row.path("channel").asText(), "无配置行默认 ws");
			assertFalse(row.path("fromAgentName").asText().isEmpty(), "应带来源展示名");
			assertFalse(row.path("toAgentName").asText().isEmpty(), "应带目标展示名");
		}
		assertEquals("Alpha", data.get(1).path("fromAgentName").asText());
		assertEquals("Bravo", data.get(1).path("toAgentName").asText());
		assertEquals("Alpha", data.get(0).path("toAgentName").asText(), "自投行两侧同名");
	}

	@Test
	@DisplayName("T_rt_api_03 保存 api 路由 → 矩阵该方向变 api，其余仍 ws，落库一行")
	void t03_saveApiRoute() throws Exception {
		String token = adminToken();
		assertEquals(200, putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"api\"}")
				.getResponse().getStatus());
		JsonNode data = listRoutes(token);
		for (JsonNode row : data) {
			boolean is12 = row.path("fromAgentId").asLong() == 1 && row.path("toAgentId").asLong() == 2;
			assertEquals(is12 ? "api" : "ws", row.path("channel").asText());
		}
		assertEquals(1, routeMapper.selectList(null).size(), "仅落一行配置");
	}

	@Test
	@DisplayName("T_rt_api_04 校验链：非法 channel / 缺 toAgentId → 422（from=to 已放开，见 t08）")
	void t04_validation() throws Exception {
		String token = adminToken();
		assertEquals(422, putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"ftp\"}")
				.getResponse().getStatus(), "非法 channel 应 422");
		assertEquals(422, putRoute(token, "{\"fromAgentId\":1,\"channel\":\"api\"}")
				.getResponse().getStatus(), "缺 toAgentId 应 422");
		assertEquals(0, routeMapper.selectList(null).size(), "校验拒绝不应落库");
	}

	@Test
	@DisplayName("T_rt_api_05 重复保存同方向 = upsert 更新（不新增行）")
	void t05_upsertSameDirection() throws Exception {
		String token = adminToken();
		putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"api\"}");
		putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"ws\"}");
		assertEquals(1, routeMapper.selectList(null).size(), "同方向应仍一行");
		assertEquals("ws", listRoutes(token).get(0).path("channel").asText());
	}

	@Test
	@DisplayName("T_rt_api_06 批量保存：多方向一次 upsert，矩阵即时反映")
	void t06_batchSave() throws Exception {
		String token = adminToken();
		MvcResult result = mockMvc.perform(put("/system/bus/routes/batch")
						.header(JwtConstants.HEADER, token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"api\"},"
								+ "{\"fromAgentId\":2,\"toAgentId\":1,\"channel\":\"api\"},"
								+ "{\"fromAgentId\":3,\"toAgentId\":1,\"channel\":\"ws\"}]"))
				.andReturn();
		assertEquals(200, result.getResponse().getStatus());
		assertEquals(3, routeMapper.selectList(null).size());
		JsonNode data = listRoutes(token);
		int apiCount = 0;
		for (JsonNode row : data) {
			if ("api".equals(row.path("channel").asText())) {
				apiCount++;
			}
		}
		assertEquals(2, apiCount, "应恰两方向 api");
	}

	@Test
	@DisplayName("T_rt_api_07 批量保存空列表 → 422")
	void t07_batchEmpty() throws Exception {
		String token = adminToken();
		MvcResult result = mockMvc.perform(put("/system/bus/routes/batch")
						.header(JwtConstants.HEADER, token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("[]"))
				.andReturn();
		assertEquals(422, result.getResponse().getStatus());
	}

	@Test
	@DisplayName("T_rt_api_08 自投方向可保存（bus-ui-v2 放开）：upsert 落行 + 矩阵即时反映")
	void t08_selfRouteAllowed() throws Exception {
		String token = adminToken();
		assertEquals(200, putRoute(token, "{\"fromAgentId\":2,\"toAgentId\":2,\"channel\":\"api\"}")
				.getResponse().getStatus(), "自投方向应可保存");
		assertEquals(1, routeMapper.selectList(null).size(), "自投方向落一行");
		JsonNode data = listRoutes(token);
		boolean selfApi = false;
		for (JsonNode row : data) {
			if (row.path("fromAgentId").asLong() == 2 && row.path("toAgentId").asLong() == 2) {
				selfApi = "api".equals(row.path("channel").asText());
			}
		}
		assertTrue(selfApi, "矩阵 2→2 应为 api");
	}

	@Test
	@DisplayName("T_rt_api_09 恢复默认：DELETE /system/bus/routes/{from} 删该发件方全部路由行（bus-ui-v2）")
	void t09_clearRoutes() throws Exception {
		String token = adminToken();
		putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":2,\"channel\":\"api\"}");
		putRoute(token, "{\"fromAgentId\":1,\"toAgentId\":3,\"channel\":\"api\"}");
		putRoute(token, "{\"fromAgentId\":2,\"toAgentId\":1,\"channel\":\"api\"}");
		assertEquals(3, routeMapper.selectList(null).size());
		MvcResult result = mockMvc.perform(delete("/system/bus/routes/1")
						.header(JwtConstants.HEADER, token))
				.andReturn();
		assertEquals(200, result.getResponse().getStatus());
		assertEquals(1, routeMapper.selectList(null).size(), "只删 from=1 的两行，2→1 保留");
		for (JsonNode row : listRoutes(token)) {
			if (row.path("fromAgentId").asLong() == 1) {
				assertEquals("ws", row.path("channel").asText(), "被删方向回落默认 ws");
			}
		}
	}

	@Test
	@DisplayName("T_rt_api_10 delivery-targets：解析 env，ws/api 两态 + 无实例行（bus-ui-v2）")
	void t10_deliveryTargets() throws Exception {
		String token = adminToken();
		MvcResult result = mockMvc.perform(get("/system/bus/delivery-targets")
					.header(JwtConstants.HEADER, token))
				.andReturn();
		assertEquals(200, result.getResponse().getStatus());
		JsonNode data = objectMapper.valueToTree(objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class)).path("data");
		assertEquals(3, data.size(), "3 个 agent 各一行");
		Map<Long, JsonNode> byId = new java.util.HashMap<>();
		for (JsonNode row : data) {
			byId.put(row.path("agentId").asLong(), row);
		}
		assertTrue(byId.get(1L).path("wsConfigured").asBoolean(), "BUS_TARGET_1 已配 → ws true");
		assertFalse(byId.get(1L).path("apiConfigured").asBoolean(), "agent1 未配 api → false");
		assertFalse(byId.get(2L).path("wsConfigured").asBoolean(), "agent2 无 BUS_TARGET → ws false");
		assertTrue(byId.get(2L).path("apiConfigured").asBoolean(), "BUS_API_TARGET_2 已配 → api true");
		assertTrue(byId.get(2L).path("apiBaseUrl").asText().contains("127.0.0.1"), "baseUrl 只读透出（不含 apiKey）");
		assertFalse(byId.get(3L).path("wsConfigured").asBoolean(), "agent3 两项均 false = 无实例");
		assertFalse(byId.get(3L).path("apiConfigured").asBoolean());
	}

	@Test
	@DisplayName("T_rt_api_11 route-stats：近 24h 方向计数（25h 前旧行不计；bus-ui-v2）")
	void t11_routeStats() throws Exception {
		insertMsg(2L, 1L, new Date());
		insertMsg(2L, 1L, new Date(System.currentTimeMillis() - 25L * 3600 * 1000));
		insertMsg(1L, 3L, new Date());
		String token = adminToken();
		MvcResult result = mockMvc.perform(get("/system/bus/route-stats")
					.header(JwtConstants.HEADER, token))
				.andReturn();
		assertEquals(200, result.getResponse().getStatus());
		JsonNode data = objectMapper.valueToTree(objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class)).path("data");
		assertEquals(2, data.size(), "25h 前旧行不计 → 仅两方向");
		Map<String, Long> counts = new java.util.HashMap<>();
		for (JsonNode row : data) {
			counts.put(row.path("fromAgentId").asLong() + "-" + row.path("toAgentId").asLong(),
					row.path("count24h").asLong());
		}
		assertEquals(1L, counts.get("2-1"), "2→1 近 24h 应 1 条（25h 前的不计）");
		assertEquals(1L, counts.get("1-3"), "1→3 近 24h 应 1 条");
	}

	/** 直插一条消息（route-stats 造数；status/create_time 显式给，事务内回滚） */
	private void insertMsg(long from, long to, Date createTime) {
		AgentMessage row = new AgentMessage();
		row.setMessageId(java.util.UUID.randomUUID().toString());
		row.setFromAgentId(from);
		row.setToAgentId(to);
		row.setMsgType("notify");
		row.setTopic("rt-stats");
		row.setTitle("路由计数行");
		row.setStatus(1);
		row.setRetryCount(0);
		row.setCreateTime(createTime);
		messageMapper.insert(row);
	}
}
