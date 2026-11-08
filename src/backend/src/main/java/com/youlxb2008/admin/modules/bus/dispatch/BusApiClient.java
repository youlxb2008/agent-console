package com.youlxb2008.admin.modules.bus.dispatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig.ApiTarget;

import lombok.extern.slf4j.Slf4j;

/**
 * api 通道投递客户端（bus-route-channel，目标 API 契约见 docs/架构设计/modules.md）
 * <p>
 * 每次投递 = 一条 HTTP 请求：
 * <ol>
 *   <li>端点：POST {baseUrl}/v1/chat/completions</li>
 *   <li>鉴权：Header Authorization: Bearer {apiKey}</li>
 *   <li>body：{"messages":[{"role":"user","content":"<消息头注入+正文，与 ws 模式同一格式>"}],
 *       "session_id":"<同 ws 模式 session_key>"}；不传 model（目标端用其激活 preset）、不传 stream</li>
 *   <li>响应：2xx = 触发成功（body 忽略，agent 回复不回投）；非流式会等 agent 循环跑完才返回，
 *       长任务可能 HTTP 超时，超时 != 未触发</li>
 * </ol>
 * 红线：apiKey 仅内存持有，禁日志打印。
 */
@Slf4j
public class BusApiClient {

    /** 默认请求超时：150s（大于 nanobot 服务端 120s；超时 != 未触发，勿调小） */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(150);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiTarget target;
    private final HttpClient http;
    private final Duration requestTimeout;

    public BusApiClient(ApiTarget target) {
        this(target, DEFAULT_TIMEOUT);
    }

    /** 测试可注入短超时（生产走 150s 默认） */
    BusApiClient(ApiTarget target, Duration requestTimeout) {
        this.target = target;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 投递一条信封（OpenAI 兼容 chat/completions 形态，messages 恰好 1 条 user）
     *
     * @param sessionId 目标会话（同 ws 模式 session_key；nanobot 侧会 api: 前缀化，属预期）
     * @param envelope  信封正文（消息头注入 + 标题 + payload，与 ws 模式完全同一格式）
     * @throws BusDeliveryException 连接失败（含连接超时）/ 4xx / 5xx（非 2xx）；
     *                              响应超时（HttpTimeoutException）= 请求已送达、目标处理中，
     *                              视为已触发正常返回（上层按 2xx 置已投递，禁重发防同消息重复注入）
     */
    public void deliver(String sessionId, String envelope) throws BusDeliveryException {
        String url = target.baseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", envelope);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(message));
        body.put("session_id", sessionId);
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new BusDeliveryException("api payload serialize failed: " + e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + target.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusDeliveryException("api delivery interrupted");
        } catch (HttpConnectTimeoutException e) {
            // 连接超时 = 连接失败：请求未送达，按投递失败折算（上层可重试）
            throw new BusDeliveryException("api delivery connect timeout: " + e.getMessage());
        } catch (HttpTimeoutException e) {
            // 响应超时 != 未触发（HttpTimeoutException 亦为 IOException 子类，须先于其单独捕获）：
            // 请求已送达目标端（非流式长任务处理中），视为已触发正常返回——上层按 2xx 置已投递，
            // 禁止重发（2026-10-03 实证：超时被当失败 3 次 retry，同消息在目标队列重复注入 3 次）
            log.info("[agent-bus] api 投递超时（请求已送达，视为已触发）: to={} session={}",
                    target.agentId(), sessionId);
            return;
        } catch (IOException e) {
            throw new BusDeliveryException("api delivery io error: " + e.getMessage());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new BusDeliveryException("api delivery http " + response.statusCode());
        }
        log.info("[agent-bus] api 投递完成: to={} session={} http={}",
                target.agentId(), sessionId, response.statusCode());
    }
}
