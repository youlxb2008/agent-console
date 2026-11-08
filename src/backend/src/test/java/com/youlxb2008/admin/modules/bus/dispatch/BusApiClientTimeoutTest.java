package com.youlxb2008.admin.modules.bus.dispatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig.ApiTarget;

/**
 * bus-api-timeout：api 通道响应超时语义回归（纯单元，无 Spring 上下文）。
 * <p>
 * 2026-10-03 实证背景：1→2 api 投递长任务响应超时被 catch (IOException) 统一折算为投递失败，
 * 上层 3 次 retry 全部真实重发，同一条消息在目标会话队列重复注入 3 次（超时 != 未送达）。
 * 修复后：HttpConnectTimeoutException（连接失败，请求未送达）仍抛 BusDeliveryException 可重试；
 * HttpTimeoutException（响应超时，请求已送达目标处理中）视为已触发正常返回——上层按 2xx 路径
 * 置 DELIVERED，不再重发。BusDispatchService 上层逻辑不动，由本测试在客户端层锁语义。
 */
class BusApiClientTimeoutTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(long sleepMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", (HttpExchange exchange) -> {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    private ApiTarget target() {
        return new ApiTarget(2L, "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
    }

    @Test
    @DisplayName("T_api_timeout_01 响应超时 → 视为已投递正常返回（不抛 BusDeliveryException=上层不 retry）")
    void t01_responseTimeoutTreatedAsDelivered() throws Exception {
        AtomicBoolean hit = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", (HttpExchange exchange) -> {
            hit.set(true);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        // 请求超时 200ms << 端点响应 1500ms：修复前 HttpTimeoutException 被 IOException 分支吞成
        // BusDeliveryException（触发重发），修复后按「已送达视为已触发」正常返回
        BusApiClient client = new BusApiClient(target(), Duration.ofMillis(200));
        assertDoesNotThrow(() -> client.deliver("api:bus-timeout-t01", "超时误判修复探针"));
        assertTrue(hit.get(), "请求应已真实到达目标端点（超时=已送达）");
    }

    @Test
    @DisplayName("T_api_timeout_02 连接失败（Connection refused）→ 仍抛 BusDeliveryException（保留可重试语义）")
    void t02_connectFailureStillFails() {
        // 127.0.0.1:1 无监听 = 立即 Connection refused（IOException 分支，与超时严格区分）
        ApiTarget dead = new ApiTarget(2L, "http://127.0.0.1:1", "test-key");
        BusApiClient client = new BusApiClient(dead, Duration.ofSeconds(5));
        BusDeliveryException e = assertThrows(BusDeliveryException.class,
                () -> client.deliver("api:bus-timeout-t02", "连接失败应可重试"));
        assertTrue(e.getMessage().contains("io error"), "连接失败走 IOException 分支: " + e.getMessage());
    }

    @Test
    @DisplayName("T_api_timeout_03 2xx 正常响应 → 正常返回（基线路径回归）")
    void t03_successPathUnchanged() throws Exception {
        startServer(0L);
        BusApiClient client = new BusApiClient(target(), Duration.ofSeconds(5));
        assertDoesNotThrow(() -> client.deliver("api:bus-timeout-t03", "基线路径"));
    }
}
