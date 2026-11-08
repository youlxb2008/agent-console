package com.youlxb2008.admin.modules.bus.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig;
import com.youlxb2008.admin.modules.bus.service.BusChannelRouteService;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig.Route;

/**
 * v4.37 bus-sessionkey-fix：dispatcher 剥 channel 前缀 + 定向落点（任务书 §改动1 单测）
 * <p>
 * 覆盖任务书四例：
 * <ol>
 *   <li>websocket:&lt;uuid&gt; → 投递端收到的 chatId/session_id = 裸 uuid</li>
 *   <li>裸 uuid → 原样透传</li>
 *   <li>bus-agent1-notify → 原样（回归）</li>
 *   <li>校验放宽：裸 uuid 不再 422（API 层，见 BusM1ApiTest T_bus_22/T_bus_23）</li>
 * </ol>
 * 投递端断言走 api 通道 stub（HTTP body.session_id）——ws 与 api 在
 * {@code deliverRow} 共用同一行 chatId 计算，stripChannelPrefix 纯逻辑另加断言覆盖 ws 语义。
 */
class BusSessionKeyFixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定测试 uuid（WebUI 聊天会话 id 形态） */
    private static final String UUID_A = "3f2b9a1c-1111-2222-3333-444455556666";

    // ===================================================================
    // 任务书例 1：websocket:<uuid> → 裸 uuid
    // ===================================================================

    @Test
    @DisplayName("T_skr_01 sessionKey=websocket:<uuid> → 投递 session_id=裸 uuid（剥前缀，防双前缀新建会话）")
    void t01_websocketPrefixStripped() throws Exception {
        assertDeliveredSessionId("websocket:" + UUID_A, UUID_A);
    }

    // ===================================================================
    // 任务书例 2：裸 uuid 原样透传
    // ===================================================================

    @Test
    @DisplayName("T_skr_02 sessionKey=裸 uuid → 投递 session_id 原样透传（无前缀不动）")
    void t02_bareUuidPassThrough() throws Exception {
        assertDeliveredSessionId(UUID_A, UUID_A);
    }

    // ===================================================================
    // 任务书例 3：bus-* 总线命名空间回归
    // ===================================================================

    @Test
    @DisplayName("T_skr_03 sessionKey=bus-agent1-notify → 原样（回归：总线命名空间不剥不拼）")
    void t03_busNamespaceRegression() throws Exception {
        assertDeliveredSessionId("bus-agent1-notify", "bus-agent1-notify");
    }

    // ===================================================================
    // stripChannelPrefix 纯逻辑（ws 通道语义 + 只剥一层）
    // ===================================================================

    @Test
    @DisplayName("T_skr_04 stripChannelPrefix：无冒号原样/只剥一层/空值安全")
    void t04_stripChannelPrefixRules() {
        assertEquals(UUID_A, BusDispatchService.stripChannelPrefix("websocket:" + UUID_A), "websocket: 剥一层");
        assertEquals(UUID_A, BusDispatchService.stripChannelPrefix("api:" + UUID_A), "api: 剥一层");
        assertEquals("bus-agent1-notify", BusDispatchService.stripChannelPrefix("bus-agent1-notify"),
                "无冒号原样返回（总线命名空间回归）");
        assertEquals("websocket:" + UUID_A,
                BusDispatchService.stripChannelPrefix("websocket:websocket:" + UUID_A),
                "双前缀只剥一层（此类入参由校验层拦截）");
        assertNull(BusDispatchService.stripChannelPrefix(null), "null 安全");
        assertEquals("", BusDispatchService.stripChannelPrefix(""), "空串安全");
    }

    // ===================================================================
    // 基建：deliverRow 走 api 通道，stub 抓投递 body
    // ===================================================================

    @SuppressWarnings("unchecked")
    private void assertDeliveredSessionId(String sessionKey, String expectedSessionId) throws Exception {
        try (StubServer stub = StubServer.start()) {
            BusMessageService messageService = mock(BusMessageService.class);
            BusChannelRouteService channels = mock(BusChannelRouteService.class);
            when(channels.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
            BusApiTargetConfig apiTargets = new BusApiTargetConfig(fixedEnv(Map.of(
                    "bus.api.target.1", "1|" + stub.baseUrl() + "|k")));
            ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
            StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
            when(provider.getIfAvailable()).thenReturn(template);
            BusRouteConfig routeConfig = new BusRouteConfig(fixedEnv(Map.of(
                    "bus.target.1", "1|ws://127.0.0.1:1/mock|sec|models=*")));
            BusDispatchService service = new BusDispatchService(routeConfig, messageService,
                    mock(AgentMapper.class), provider, channels, apiTargets, 150000L, false);

            AgentMessage row = pendingRow(sessionKey);
            service.deliverRow(new Route(1L, "ws://127.0.0.1:1/mock", "sec", Set.of("*")), row);

            assertEquals(1, stub.bodies().size(), "恰一次 HTTP 投递");
            JsonNode body = MAPPER.readTree(stub.bodies().get(0));
            assertEquals(expectedSessionId, body.get("session_id").asText(),
                    "投递 session_id 应为: " + expectedSessionId + "（入参 sessionKey=" + sessionKey + "）");
        }
    }

    private AgentMessage pendingRow(String sessionKey) {
        AgentMessage row = new AgentMessage();
        row.setId(900L);
        row.setMessageId("mid-skr-" + System.nanoTime());
        row.setFromAgentId(1L);
        row.setToAgentId(1L);
        row.setMsgType("notify");
        row.setTopic("debug");
        row.setTitle("总线调试");
        row.setSessionKey(sessionKey);
        row.setStatus(BusMessageService.STATUS_PENDING);
        row.setRetryCount(0);
        return row;
    }

    private static MockEnvironment fixedEnv(Map<String, String> props) {
        MockEnvironment env = new MockEnvironment();
        props.forEach(env::setProperty);
        return env;
    }

    /** 本地 stub：记录请求 body 并返回 200（同 BusRouteChannelTest.StubServer 缩版） */
    private record StubServer(HttpServer server, String baseUrl, List<byte[]> bodies)
            implements AutoCloseable {

        static StubServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            List<byte[]> bodies = new CopyOnWriteArrayList<>();
            server.createContext("/", (exchange) -> {
                bodies.add(exchange.getRequestBody().readAllBytes());
                byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            return new StubServer(server, "http://127.0.0.1:" + server.getAddress().getPort(), bodies);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
