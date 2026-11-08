package com.youlxb2008.admin.modules.bus.dispatch;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.youlxb2008.admin.modules.bus.service.BusRouteConfig.Route;

import lombok.extern.slf4j.Slf4j;

/**
 * 目标 gateway WS 客户端（M1，投递协议见 docs/架构设计/modules.md）
 * <p>
 * 每个目标路由一条常连接：
 * <ol>
 *   <li>bootstrap：GET {httpBase}/webui/bootstrap，头 Authorization: Bearer {secret} → {"token":"..."}；
 *       token 约 300s TTL，每次连接前现取</li>
 *   <li>连接：ws://{host}/?client_id=agc-bus-dispatcher-{ts}&amp;token={token}；连上先收 ready 帧</li>
 *   <li>注入帧（typed envelope）：{"type":"message","chat_id":"...","content":"..."}；
 *       每帧显式带 chat_id（不带会落到连接默认会话）</li>
 *   <li>model 指定：msg.model 非空时先发 content="/model {preset}"（同 chat_id），
 *       等 event=runtime_model_updated 回帧或 2s 超时后再发正文（WS 协议无 per-message model 字段）</li>
 *   <li>错误回帧 {"event":"error","detail":...} → 按投递失败处理</li>
 *   <li>ping 20s 保活 + 断线后下次投递自动重连</li>
 * </ol>
 * 红线：bootstrap secret / token 仅内存持有，禁入库、禁日志打印（连接 URL 打印时抹除 token 参数）。
 * <p>
 * 投递成功定义 = WS send 成功且错误观察窗（2s）内未收到 event=error 帧；
 * 不等 agent 回复帧（回合可能数分钟，不阻塞队列，方案 §6）。
 */
@Slf4j
public class BusGatewayClient {

    /** 发送后错误观察窗（收到 event=error 视为失败） */
    private static final long ERROR_WINDOW_MS = 2000;
    /** /model 指定后等待 runtime_model_updated 的超时 */
    private static final long MODEL_ACK_TIMEOUT_MS = 2000;
    /** bootstrap / 连接建立超时 */
    private static final long CONNECT_TIMEOUT_MS = 10_000;
    /** sendText 完成等待超时 */
    private static final long SEND_TIMEOUT_MS = 10_000;
    /** ping 周期（秒） */
    private static final long PING_INTERVAL_SECONDS = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong CLIENT_SEQ = new AtomicLong(0);

    private final Route route;
    private final HttpClient http;
    private final ScheduledExecutorService pingExecutor;

    /** 连接状态（volatile：ping 线程与投递线程跨线程可见） */
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean readyReceived;
    /** 投递后错误观察窗（每次发送前重置） */
    private volatile CompletableFuture<String> pendingError;
    /** /model 指定后的 runtime_model_updated 信号（每次发送前重置） */
    private volatile CompletableFuture<Boolean> modelUpdated;

    public BusGatewayClient(Route route) {
        this.route = route;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        this.pingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bus-gw-ping-" + route.toAgentId());
            t.setDaemon(true);
            return t;
        });
        this.pingExecutor.scheduleAtFixedRate(this::ping, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 投递一条信封（含 model 前置帧与错误观察窗）
     *
     * @param chatId      目标会话（bus-* 命名空间，每帧显式携带）
     * @param envelope    信封正文
     * @param modelPreset 指定模型（空 = 收件人默认 preset，不发 /model 帧）
     * @throws BusDeliveryException 连接失败 / 发送失败 / 错误回帧
     */
    public void deliver(String chatId, String envelope, String modelPreset) throws BusDeliveryException {
        ensureConnected();
        // model 指定：先发 /model 帧等 runtime_model_updated 或 2s 超时，再发正文（§2.5.4）
        if (modelPreset != null && !modelPreset.isBlank()) {
            sendFrame(chatId, "/model " + modelPreset);
            awaitModelUpdated();
        }
        sendFrame(chatId, envelope);
        awaitErrorWindow();
    }

    /** 当前连接是否可用 */
    public boolean isConnected() {
        return connected;
    }

    /** 关闭（停机时调用） */
    public void close() {
        connected = false;
        pingExecutor.shutdownNow();
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 停机路径，忽略关闭异常
            }
        }
    }

    // ==================== 连接管理 ====================

    private void ensureConnected() throws BusDeliveryException {
        if (connected && webSocket != null) {
            return;
        }
        try {
            connect();
        } catch (Exception e) {
            // 连接失败 ≠ 消息失败：抛给调用方计 retry，消息留表/队列等恢复（§2.5.5）
            throw new BusDeliveryException("connect failed: " + safeMessage(e));
        }
    }

    /** bootstrap 取 token → WS 连接 → 等 ready 帧（任务书 §2.5.1/2） */
    private synchronized void connect() throws Exception {
        if (connected && webSocket != null) {
            return;
        }
        // 1. bootstrap（token 约 300s TTL，每次连接前现取）
        String token = fetchBootstrapToken();
        // 2. 连接（client_id 唯一，token 挂 query）
        String clientId = "agc-bus-dispatcher-" + route.toAgentId() + "-" + System.currentTimeMillis();
        URI uri = URI.create(route.wsUrl() + "/?client_id=" + clientId
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        log.info("[agent-bus] 连接目标 gateway: to={} url={}（token 已抹除）", route.toAgentId(), stripToken(uri.toString()));
        readyReceived = false;
        CompletableFuture<WebSocket> future = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .buildAsync(uri, new FrameListener());
        WebSocket ws;
        try {
            ws = future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("ws handshake timeout");
        }
        this.webSocket = ws;
        ws.request(1);
        // 3. 等 ready 帧（连上先收一帧 ready JSON；最多等 5s，超时不阻断——协议异常由后续 error 帧兜底）
        long deadline = System.currentTimeMillis() + 5000;
        while (!readyReceived && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        connected = true;
        log.info("[agent-bus] 目标 gateway 已连接: to={} ready={}", route.toAgentId(), readyReceived);
    }

    private String fetchBootstrapToken() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(route.httpBaseUrl() + "/webui/bootstrap"))
                .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .header("Authorization", "Bearer " + route.secret())
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("bootstrap http " + resp.statusCode());
        }
        JsonNode node = MAPPER.readTree(resp.body());
        String token = node.path("token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("bootstrap 响应无 token 字段");
        }
        return token;
    }

    private void ping() {
        try {
            WebSocket ws = this.webSocket;
            if (connected && ws != null) {
                ws.sendPing(ByteBuffer.allocate(0));
            }
        } catch (Exception e) {
            log.debug("[agent-bus] ping 失败（下次投递时重连）: to={} err={}", route.toAgentId(), safeMessage(e));
        }
    }

    // ==================== 帧发送 ====================

    /** 发 typed envelope 帧：{"type":"message","chat_id":...,"content":...}，每帧显式 chat_id */
    private void sendFrame(String chatId, String content) throws BusDeliveryException {
        Map<String, String> frame = new LinkedHashMap<>();
        frame.put("type", "message");
        frame.put("chat_id", chatId);
        frame.put("content", content);
        String json;
        try {
            json = MAPPER.writeValueAsString(frame);
        } catch (Exception e) {
            throw new BusDeliveryException("frame serialize failed: " + safeMessage(e));
        }
        // 重置观察窗（仅观察本帧引发的 error 回帧）
        CompletableFuture<String> err = new CompletableFuture<>();
        this.pendingError = err;
        try {
            webSocket.sendText(json, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusDeliveryException("send interrupted");
        } catch (ExecutionException | TimeoutException | IllegalStateException e) {
            connected = false;
            throw new BusDeliveryException("send failed: " + safeMessage(e));
        }
    }

    /** 错误观察窗：ERROR_WINDOW_MS 内收到 event=error → 投递失败 */
    private void awaitErrorWindow() throws BusDeliveryException {
        CompletableFuture<String> err = this.pendingError;
        if (err == null) {
            return;
        }
        try {
            String detail = err.get(ERROR_WINDOW_MS, TimeUnit.MILLISECONDS);
            throw new BusDeliveryException("gateway error frame: " + detail);
        } catch (TimeoutException expected) {
            // 观察窗内无错误回帧 → 协议层送达
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusDeliveryException("error window interrupted");
        } catch (ExecutionException e) {
            throw new BusDeliveryException("error frame wait failed: " + safeMessage(e));
        }
    }

    /** /model 指定后等 runtime_model_updated 回帧；2s 超时按协议允许继续（任务书 §2.5.4） */
    private void awaitModelUpdated() {
        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        this.modelUpdated = ack;
        try {
            ack.get(MODEL_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            log.warn("[agent-bus] /model 回帧超时（{}ms），按协议继续发送正文: to={}", MODEL_ACK_TIMEOUT_MS, route.toAgentId());
        } catch (Exception ignored) {
            // 回执等待失败不阻断正文发送
        }
    }

    // ==================== 收帧 ====================

    private void handleFrame(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            String event = node.path("event").asText(null);
            if ("ready".equals(event)) {
                readyReceived = true;
                return;
            }
            if ("error".equals(event)) {
                String detail = node.path("detail").asText("unknown");
                CompletableFuture<String> err = this.pendingError;
                if (err != null) {
                    err.complete(detail);
                }
                log.warn("[agent-bus] 收到 error 回帧: to={} detail={}", route.toAgentId(), detail);
                return;
            }
            if ("runtime_model_updated".equals(event)) {
                CompletableFuture<Boolean> ack = this.modelUpdated;
                if (ack != null) {
                    ack.complete(true);
                }
                return;
            }
            // 其余帧（消息流 delta 等）忽略
        } catch (Exception e) {
            log.debug("[agent-bus] 非法帧忽略: {}", raw.length() > 120 ? raw.substring(0, 120) + "..." : raw);
        }
    }

    private String stripToken(String url) {
        return url.replaceAll("([?&])token=[^&]*", "$1token=***");
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    /**
     * WS 帧监听：分片拼接 + 事件分发 + 断线标记
     */
    private class FrameListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handleFrame(frame);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            connected = false;
            log.info("[agent-bus] 目标 gateway 连接关闭: to={} code={} reason={}", route.toAgentId(), statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            connected = false;
            log.warn("[agent-bus] 目标 gateway 连接异常: to={} err={}", route.toAgentId(), error.getMessage());
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, ByteBuffer message) {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket ws, ByteBuffer message) {
            ws.request(1);
            return null;
        }
    }
}
