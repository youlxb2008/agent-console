package com.youlxb2008.admin.modules.bus.dispatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.mapper.BusRouteMapper;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig.ApiTarget;
import com.youlxb2008.admin.modules.bus.service.BusChannelRouteService;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig.Route;

/**
 * bus-route-channel 通道分叉纯逻辑测试（不起 Spring 上下文，Mockito + JDK HttpServer stub）
 * <p>
 * 覆盖：api 目标配置解析（BUS_API_TARGET 行）/ 通道判定（无行默认 ws、api 行、非法值防御）/
 * api payload 组装（POST {base}/v1/chat/completions、Bearer、messages 单条 user、session_id）/
 * 2xx 成功 / 非 2xx 失败 / 响应超时按已投递折算（连接失败仍可重试）/ 投递分叉（api 走 HTTP、缺配置降级 ws 记 last_error 备注）/
 * api 重试上限 3（ws 维持 5）。
 */
class BusRouteChannelTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static MockEnvironment fixedEnv(Map<String, String> props) {
		MockEnvironment env = new MockEnvironment();
		props.forEach(env::setProperty);
		return env;
	}

	// ===================================================================
	// api 目标配置解析（BUS_API_TARGET_*）
	// ===================================================================

	@Test
	@DisplayName("T_rt_01 api 目标行解析：三段 agentId|baseUrl|apiKey；非法输入抛错；toString 抹 key")
	void t01_apiTargetParse() {
		ApiTarget t = BusApiTargetConfig.parseLine("2|http://your-host:8900|apikey-xxxx");
		assertEquals(2, t.agentId());
		assertEquals("http://your-host:8900", t.baseUrl());
		assertEquals("apikey-xxxx", t.apiKey());
		assertFalse(t.toString().contains("apikey-xxxx"), "toString 禁带 apiKey");

		assertThrows(IllegalArgumentException.class,
				() -> BusApiTargetConfig.parseLine("1|ftp://h:1|k"), "baseUrl 非 http(s) 应抛");
		assertThrows(IllegalArgumentException.class,
				() -> BusApiTargetConfig.parseLine("1|http://h:1"), "两段应抛");
		assertThrows(IllegalArgumentException.class,
				() -> BusApiTargetConfig.parseLine("abc|http://h:1|k"), "agentId 非数字应抛");
		assertThrows(IllegalArgumentException.class,
				() -> BusApiTargetConfig.parseLine("1|http://h:1| "), "apiKey 空应抛");
	}

	@Test
	@DisplayName("T_rt_02 api 目标装载：多行解析 + 目标查询 + 非法行跳过不阻断")
	void t02_apiTargetConfig() {
		BusApiTargetConfig config = new BusApiTargetConfig(fixedEnv(Map.of(
				"bus.api.target.1", "2|http://h1:8901|k1",
				"bus.api.target.2", "3|https://h2:8902|k2",
				"bus.api.target.3", "bad-line")));
		assertTrue(config.hasTarget(2));
		assertTrue(config.hasTarget(3));
		assertFalse(config.hasTarget(9), "未配置目标应缺省");
		assertEquals("http://h1:8901", config.targetOf(2).orElseThrow().baseUrl());
		assertEquals(Optional.empty(), config.targetOf(9));
		assertEquals(2, config.allTargets().size(), "非法行应跳过不计数");
	}

	// ===================================================================
	// 通道判定（t_bus_route 无行默认 ws）
	// ===================================================================

	@Test
	@DisplayName("T_rt_03 通道判定：无配置行=ws / api 行=api / 非法值防御=ws / 空入参=ws")
	void t03_resolveChannel() {
		BusRouteMapper routeMapper = mock(BusRouteMapper.class);
		BusChannelRouteService service = new BusChannelRouteService(routeMapper, mock(AgentMapper.class));

		when(routeMapper.selectOne(any())).thenReturn(null);
		assertEquals(BusRoute.CHANNEL_WS, service.resolveChannel(5L, 1L), "无配置行应默认 ws");

		BusRoute apiRow = new BusRoute();
		apiRow.setFromAgentId(5L);
		apiRow.setToAgentId(1L);
		apiRow.setChannel(BusRoute.CHANNEL_API);
		when(routeMapper.selectOne(any())).thenReturn(apiRow);
		assertEquals(BusRoute.CHANNEL_API, service.resolveChannel(5L, 1L), "api 行应判 api");

		apiRow.setChannel("smtp");
		assertEquals(BusRoute.CHANNEL_WS, service.resolveChannel(5L, 1L), "非法 channel 防御性按 ws");

		assertEquals(BusRoute.CHANNEL_WS, service.resolveChannel(null, 1L), "空 from 防御性按 ws");
	}

	// ===================================================================
	// api 投递客户端（JDK HttpServer stub）
	// ===================================================================

	/** 本地 stub：记录请求并返回指定状态码；sleepMs 模拟慢响应（超时用例） */
	private record StubServer(HttpServer server, String baseUrl, List<HttpExchange> exchanges,
			List<byte[]> bodies) implements AutoCloseable {

		static StubServer start(int status, long sleepMs) throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			List<HttpExchange> exchanges = new CopyOnWriteArrayList<>();
			List<byte[]> bodies = new CopyOnWriteArrayList<>();
			server.createContext("/", (exchange) -> {
				exchanges.add(exchange);
				bodies.add(exchange.getRequestBody().readAllBytes());
				if (sleepMs > 0) {
					try {
						Thread.sleep(sleepMs);
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
				}
				byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(status, resp.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(resp);
				}
			});
			server.setExecutor(Executors.newSingleThreadExecutor());
			server.start();
			return new StubServer(server, "http://127.0.0.1:" + server.getAddress().getPort(),
					exchanges, bodies);
		}

		@Override
		public void close() {
			server.stop(0);
		}
	}

	@Test
	@DisplayName("T_rt_04 api 投递 200：POST {base}/v1/chat/completions + Bearer + messages 单条 user + session_id")
	void t04_apiDeliverOk() throws Exception {
		try (StubServer stub = StubServer.start(200, 0)) {
			BusApiClient client = new BusApiClient(
					new ApiTarget(2, stub.baseUrl(), "key-xyz"), Duration.ofSeconds(5));
			client.deliver("bus-agent5-notify", "信封正文");
			assertEquals(1, stub.exchanges().size());
			HttpExchange exchange = stub.exchanges().get(0);
			assertEquals("POST", exchange.getRequestMethod());
			assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
			assertEquals("Bearer key-xyz", exchange.getRequestHeaders().getFirst("Authorization"));
			assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
			JsonNode body = MAPPER.readTree(stub.bodies().get(0));
			assertEquals(1, body.path("messages").size(), "messages 恰好 1 条");
			assertEquals("user", body.path("messages").get(0).path("role").asText());
			assertEquals("信封正文", body.path("messages").get(0).path("content").asText());
			assertEquals("bus-agent5-notify", body.path("session_id").asText());
			assertTrue(body.path("model").isMissingNode(), "不传 model（目标端用激活 preset）");
			assertNull(body.path("stream").asText(null), "不传 stream");
		}
	}

	@Test
	@DisplayName("T_rt_05 api 投递 5xx → BusDeliveryException（status 不置 1，走 retry）")
	void t05_apiDeliver5xx() throws Exception {
		try (StubServer stub = StubServer.start(500, 0)) {
			BusApiClient client = new BusApiClient(
					new ApiTarget(2, stub.baseUrl(), "key-xyz"), Duration.ofSeconds(5));
			BusDeliveryException e = assertThrows(BusDeliveryException.class,
					() -> client.deliver("sess", "信封"));
			assertTrue(e.getMessage().contains("http 500"), "应带 HTTP 状态码: " + e.getMessage());
		}
	}

	@Test
	@DisplayName("T_rt_06 api 响应超时（请求已送达）→ 正常返回按已投递折算，不再重发")
	void t06_apiDeliverTimeout() throws Exception {
		try (StubServer stub = StubServer.start(200, 1200)) {
			BusApiClient client = new BusApiClient(
					new ApiTarget(2, stub.baseUrl(), "key-xyz"), Duration.ofMillis(300));
			// 响应超时 = 目标已收到请求正在处理：正常返回，上层按 2xx 路径置 DELIVERED，不再 retry 重发
			// （连接失败仍抛 BusDeliveryException 的语义由 BusApiClientTimeoutTest 覆盖）
			assertDoesNotThrow(() -> client.deliver("sess", "信封"));
		}
	}

	// ===================================================================
	// deliverRow 通道分叉 + 降级 + retry 上限
	// ===================================================================

	@SuppressWarnings("unchecked")
	private BusDispatchService newService(BusMessageService messageService,
			BusChannelRouteService channels, BusApiTargetConfig apiTargets) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
		when(provider.getIfAvailable()).thenReturn(template);
		BusRouteConfig routeConfig = new BusRouteConfig(fixedEnv(Map.of(
				"bus.target.1", "1|ws://127.0.0.1:1/mock|sec|models=*")));
		return new BusDispatchService(routeConfig, messageService, mock(AgentMapper.class), provider,
				channels, apiTargets, 150000L, false);
	}

	private Route route1() {
		return new Route(1L, "ws://127.0.0.1:1/mock", "sec", Set.of("*"));
	}

	private AgentMessage pendingRow(long id, String messageId, int retryCount) {
		AgentMessage row = new AgentMessage();
		row.setId(id);
		row.setMessageId(messageId);
		row.setFromAgentId(5L);
		row.setToAgentId(1L);
		row.setMsgType("notify");
		row.setTopic("t");
		row.setTitle("标题");
		row.setStatus(BusMessageService.STATUS_PENDING);
		row.setRetryCount(retryCount);
		return row;
	}

	@Test
	@DisplayName("T_rt_07 通道=api：走 HTTP 直投（不可达目标）→ retry+1，last_error 为 api 形态")
	void t07_apiChannelDeliversViaHttp() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusChannelRouteService channels = mock(BusChannelRouteService.class);
		when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
		BusApiTargetConfig apiTargets = new BusApiTargetConfig(fixedEnv(Map.of(
				"bus.api.target.1", "1|http://127.0.0.1:1|k")));
		BusDispatchService service = newService(messageService, channels, apiTargets);

		service.deliverRow(route1(), pendingRow(200L, "mid-api", 0));

		ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
		Mockito.verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(1, patch.getRetryCount());
		assertNotNull(patch.getLastError());
		assertTrue(patch.getLastError().startsWith("api delivery"), "应为 api 通道失败形态: " + patch.getLastError());
		assertNull(patch.getStatus(), "首败不应终态化");
	}

	@Test
	@DisplayName("T_rt_08 通道=api 重试上限 3：第 3 次失败即 status=3（ws 维持 5）")
	void t08_apiRetryCap() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusChannelRouteService channels = mock(BusChannelRouteService.class);
		when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
		BusApiTargetConfig apiTargets = new BusApiTargetConfig(fixedEnv(Map.of(
				"bus.api.target.1", "1|http://127.0.0.1:1|k")));
		BusDispatchService service = newService(messageService, channels, apiTargets);

		for (int retry = 0; retry < 3; retry++) {
			service.deliverRow(route1(), pendingRow(201L, "mid-cap", retry));
		}
		ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
		Mockito.verify(messageService, Mockito.times(3)).updateRow(captor.capture());
		List<AgentMessage> patches = captor.getAllValues();
		assertNull(patches.get(0).getStatus());
		assertNull(patches.get(1).getStatus());
		assertEquals(3, patches.get(2).getRetryCount(), "api 第 3 次失败应 retry=3");
		assertEquals(BusMessageService.STATUS_FAILED, patches.get(2).getStatus(), "api 通道 3 次即终态（连接类失败防无限重发）");
	}

	@Test
	@DisplayName("T_rt_09 通道=api 缺 BUS_API_TARGET 配置 → 降级 ws，先记 last_error 备注")
	void t09_apiMissingTargetFallsBackWs() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusChannelRouteService channels = mock(BusChannelRouteService.class);
		when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
		BusApiTargetConfig apiTargets = mock(BusApiTargetConfig.class);
		when(apiTargets.targetOf(anyLong())).thenReturn(Optional.empty());
		BusDispatchService service = newService(messageService, channels, apiTargets);

		service.deliverRow(route1(), pendingRow(202L, "mid-fb", 0));

		ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
		Mockito.verify(messageService, Mockito.times(2)).updateRow(captor.capture());
		List<AgentMessage> patches = captor.getAllValues();
		assertEquals(BusDispatchService.API_FALLBACK_NOTE, patches.get(0).getLastError(),
				"降级时应先记 last_error 备注");
		assertEquals(1, patches.get(1).getRetryCount(), "随后走 ws 投递（不可达即计入 retry）");
		InOrder inOrder = Mockito.inOrder(messageService);
		inOrder.verify(messageService).updateRow(patches.get(0));
		inOrder.verify(messageService).updateRow(patches.get(1));
	}

	@Test
	@DisplayName("T_rt_10 通道=ws 回归：存量路径零变化（不可达 → retry+1，不触 api）")
	void t10_wsChannelRegression() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusChannelRouteService channels = mock(BusChannelRouteService.class);
		when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_WS);
		BusApiTargetConfig apiTargets = new BusApiTargetConfig(fixedEnv(Map.of(
				"bus.api.target.1", "1|http://127.0.0.1:1|k")));
		BusDispatchService service = newService(messageService, channels, apiTargets);

		service.deliverRow(route1(), pendingRow(203L, "mid-ws", 0));

		ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
		Mockito.verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(1, patch.getRetryCount());
		assertFalse(String.valueOf(patch.getLastError()).startsWith("api delivery"),
				"ws 通道失败不应是 api 形态");
	}

	@Test
	@DisplayName("T_rt_11 api 投递成功路径（stub 200）：status=1 + delivered_at + session_key 回填")
	void t11_apiDeliverSuccessMarksDelivered() throws Exception {
		try (StubServer stub = StubServer.start(200, 0)) {
			BusMessageService messageService = mock(BusMessageService.class);
			BusChannelRouteService channels = mock(BusChannelRouteService.class);
			when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
			BusApiTargetConfig apiTargets = new BusApiTargetConfig(fixedEnv(Map.of(
					"bus.api.target.1", "1|" + stub.baseUrl() + "|k")));
			BusDispatchService service = newService(messageService, channels, apiTargets);

			service.deliverRow(route1(), pendingRow(204L, "mid-ok", 0));

			ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
			Mockito.verify(messageService).updateRow(captor.capture());
			AgentMessage patch = captor.getValue();
			assertEquals(BusMessageService.STATUS_DELIVERED, patch.getStatus(), "2xx 应置 status=1");
			assertNotNull(patch.getDeliveredAt(), "应记 delivered_at");
			assertEquals("bus-agent5-notify", patch.getSessionKey(), "空 sessionKey 应回填自动会话");
			// ws 态（apiChannel=false 落 ws）同分支结构保证；真实 ws 消息回填由 dev e2e 验证
			assertEquals(BusRoute.CHANNEL_API, patch.getChannel(), "api 投递成功应回填 channel=api（bus-ui-v2）");
			assertEquals(1, stub.exchanges().size(), "恰一次 HTTP 投递");
		}
	}
}
