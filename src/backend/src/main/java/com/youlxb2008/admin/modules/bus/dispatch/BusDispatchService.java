package com.youlxb2008.admin.modules.bus.dispatch;

import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroups;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig.ApiTarget;
import com.youlxb2008.admin.modules.bus.service.BusChannelRouteService;
import com.youlxb2008.admin.modules.bus.service.BusEnvelopeFormatter;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig.Route;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 消息总线 dispatcher（M1 核心，方案 §5.2/§6）
 * <p>
 * 线程模型：独立线程池（非 @Scheduled 串行）——每个目标分片一个消费循环，目标间互不阻塞；
 * M1 单目标（stream:1）单循环。
 * <p>
 * 消费循环：
 * <ol>
 *   <li>XAUTOCLAIM 认领 PEL 中闲置 &gt;60s 的消息（重启恢复 + 竞争兜底）</li>
 *   <li>XREADGROUP GROUP bus-dispatcher COUNT 10 BLOCK 5000 拉新消息（入 PEL）</li>
 *   <li>投递前 SELECT status，≠0 直接 XACK 跳过（消费侧幂等，补偿重灌安全）</li>
 *   <li>WS 发送（成功 = send 成功且错误观察窗无 error 帧，不等 agent 回复帧）：
 *       成功 → XACK + status=1/delivered_at/回填 session_key；
 *       失败 → retry_count+1 + last_error（≤500），指数退避（1s/2s/4s/8s/16s）≤5 次 → status=3</li>
 *   <li>补偿：每 60s 扫 status=0 且 create_time &lt; NOW()-2min 的行重灌 XADD（message_id 幂等）；
 *       retry_count&gt;0 的行按指数退避窗过滤（update_time + backoff 未到的跳过）</li>
 * </ol>
 * 优雅停机：未 ACK 消息留 PEL，重启后 XAUTOCLAIM 认领；已 send 未更表的窗口由补偿扫表覆盖
 * （at-least-once，接收侧按信封 messageId 去重）。
 */
@Slf4j
@Component
public class BusDispatchService {

    /** 消费组名（每 Stream 一组，方案 §3.2） */
    public static final String GROUP = "bus-dispatcher";
    /** XREADGROUP 单批条数 */
    private static final long BATCH_COUNT = 10;
    /** XREADGROUP BLOCK 时长（任务书 §2.4：BLOCK 5000） */
    private static final Duration BLOCK = Duration.ofSeconds(5);
    /** PEL 认领最小闲置时长 */
    private static final Duration CLAIM_MIN_IDLE = Duration.ofSeconds(60);
    /** 补偿扫表周期（ms） */
    private static final long COMPENSATE_INTERVAL_MS = 60_000;
    /** 循环异常后的休眠（ms） */
    private static final long LOOP_ERROR_SLEEP_MS = 5000;
    /** last_error 截断长度（列宽 500） */
    private static final int LAST_ERROR_MAX = 500;
    /** api 通道重试上限（bus-route-channel：HTTP 超时重发易双发，3 次即停走告警） */
    public static final int API_MAX_RETRY = 3;
    /** api 目标缺配置降级 ws 时记录的 last_error 备注 */
    public static final String API_FALLBACK_NOTE = "api target missing, fallback ws";

    /** busfix-2 派活行 payload 顶层 window 字段名（裸 uuid = worker 回执定向投递目标） */
    static final String WINDOW_FIELD = "window";
    /** busfix-2 payload 解析器（只读、线程安全；解析失败按未命中处理） */
    private static final ObjectMapper PAYLOAD_MAPPER = new ObjectMapper();

    private final BusRouteConfig routeConfig;
    private final BusMessageService messageService;
    private final AgentMapper agentMapper;
    private final BusChannelRouteService channelRouteService;
    private final BusApiTargetConfig apiTargetConfig;
    private final Duration apiTimeout;
    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Long, BusGatewayClient> clients = new HashMap<>();
    private final Map<Long, BusApiClient> apiClients = new HashMap<>();
    private final List<ExecutorService> loopExecutors = new java.util.ArrayList<>();

    public BusDispatchService(BusRouteConfig routeConfig, BusMessageService messageService,
            AgentMapper agentMapper, ObjectProvider<StringRedisTemplate> redisProvider,
            BusChannelRouteService channelRouteService, BusApiTargetConfig apiTargetConfig,
            @Value("${bus.api.timeout-ms:150000}") long apiTimeoutMs,
            @Value("${bus.dispatch.enabled:true}") boolean enabled) {
        this.routeConfig = routeConfig;
        this.messageService = messageService;
        this.agentMapper = agentMapper;
        this.channelRouteService = channelRouteService;
        this.apiTargetConfig = apiTargetConfig;
        this.apiTimeout = Duration.ofMillis(apiTimeoutMs);
        this.redisTemplate = redisProvider.getIfAvailable();
        this.enabled = enabled;
    }

    /** 启动每目标消费循环（Redis 缺失/未启用时跳过——dev 无 Redis 不阻塞启动） */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[agent-bus] dispatcher 未启用（bus.dispatch.enabled=false），跳过消费循环");
            return;
        }
        if (redisTemplate == null) {
            log.warn("[agent-bus] Redis 不可用，dispatcher 不启动（消息仅落表，接入 Redis 后重启生效）");
            return;
        }
        List<Route> routes = routeConfig.allRoutes();
        if (routes.isEmpty()) {
            log.warn("[agent-bus] 无路由配置（BUS_TARGET_* 未注入），dispatcher 不启动");
            return;
        }
        running.set(true);
        for (Route route : routes) {
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bus-dispatcher-" + route.toAgentId());
                t.setDaemon(true);
                return t;
            });
            loopExecutors.add(executor);
            executor.submit(() -> consumeLoop(route));
        }
        log.info("[agent-bus] dispatcher 已启动: {} 个目标循环 {}", routes.size(), routes.stream().map(Route::toAgentId).toList());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        for (ExecutorService executor : loopExecutors) {
            executor.shutdownNow();
        }
        synchronized (clients) {
            clients.values().forEach(BusGatewayClient::close);
        }
        log.info("[agent-bus] dispatcher 已停机（未 ACK 消息留 PEL，重启后 XAUTOCLAIM 认领）");
    }

    // ==================== 消费循环 ====================

    void consumeLoop(Route route) {
        String key = BusRouteConfig.streamKey(route.toAgentId());
        String consumer = GROUP + "-" + route.toAgentId();
        long lastCompensate = 0;
        log.info("[agent-bus] 消费循环启动: key={} group={} consumer={}", key, GROUP, consumer);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                ensureGroup(key);
                // 重启恢复：认领 PEL 中闲置超时的消息（含历史实例遗留）
                claimPending(route, key, consumer);
                // 拉新消息（BLOCK 5s，空转醒来后顺带检查补偿计时）
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumer),
                        StreamReadOptions.empty().count(BATCH_COUNT).block(BLOCK),
                        StreamOffset.create(key, ReadOffset.lastConsumed()));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        handleRecord(route, record);
                    }
                }
                // 补偿扫表（60s 周期）
                long now = System.currentTimeMillis();
                if (now - lastCompensate >= COMPENSATE_INTERVAL_MS) {
                    compensate(route);
                    lastCompensate = now;
                }
            } catch (Exception e) {
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("[agent-bus] 消费循环异常（{}ms 后重试）: key={} err={}", LOOP_ERROR_SLEEP_MS, key, e.getMessage());
                try {
                    TimeUnit.MILLISECONDS.sleep(LOOP_ERROR_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("[agent-bus] 消费循环退出: key={}", key);
    }

    /**
     * 创建消费组（offset=0 从头消费，防「先 XADD 后建组」丢消息）。包级便于单测。
     * <p>
     * v2 修复（Redis Stream 消费组重复创建实证死循环）：改「先查后建」——XINFO GROUPS 判断组
     * 已存在直接跳过 createGroup。原因：Lettuce 把 Redis 错误响应统一包成
     * {@code RedisCommandExecutionException("Error in execution")}（真实 detail 在 cause 链），
     * 原实现的 {@code getMessage().contains("BUSYGROUP")} 永远匹配不上 → 组已存在时每轮
     * createGroup 必抛 → 消费循环永远走不到 XREADGROUP（group last-delivered-id 停在 0-0，
     * 消息堆积无人消费）。createGroup 仍保留 cause 链 BUSYGROUP 兜底（并发建组竞争窗口）。
     */
    void ensureGroup(String key) {
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        if (groupExists(streamOps, key)) {
            return;
        }
        try {
            streamOps.createGroup(key, ReadOffset.from("0"), GROUP);
            log.info("[agent-bus] 消费组已创建: key={} group={}（offset=0）", key, GROUP);
        } catch (Exception e) {
            // XINFO 与 XGROUP CREATE 之间的并发竞争窗口：别的实例抢先建组 → BUSYGROUP，吞掉
            if (!containsBusyGroup(e)) {
                throw e;
            }
            log.debug("[agent-bus] 消费组已被并发创建，跳过: key={} group={}", key, GROUP);
        }
    }

    /**
     * XINFO GROUPS 查组是否已存在。查询失败（stream 尚不存在 / 连接抖动）按「不存在」
     * 处理——createGroup 对不存在的 stream 会连 stream 一起建，BUSYGROUP 兜底覆盖竞争窗口，
     * 不因查询故障跳过建组。
     */
    private boolean groupExists(StreamOperations<String, Object, Object> streamOps, String key) {
        try {
            XInfoGroups groups = streamOps.groups(key);
            if (groups == null) {
                return false;
            }
            for (XInfoGroup group : groups) {
                if (GROUP.equals(group.groupName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("[agent-bus] XINFO GROUPS 查询失败，按组不存在处理: key={} err={}", key, e.getMessage());
            return false;
        }
    }

    /** 沿 cause 链找 BUSYGROUP（防自引用环；Lettuce 错误 detail 在 cause 里） */
    private static boolean containsBusyGroup(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause() == cur ? null : cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /** XAUTOCLAIM 认领 PEL 闲置超时的消息（Spring Data Redis claim = XAUTOCLAIM 语义） */
    private void claimPending(Route route, String key, String consumer) {
        StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
        PendingMessages pending = streamOps.pending(key, GROUP, Range.unbounded(), 20);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        List<RecordId> stale = new java.util.ArrayList<>();
        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(CLAIM_MIN_IDLE) >= 0) {
                stale.add(pm.getId());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        List<MapRecord<String, Object, Object>> claimed = streamOps.claim(
                key, GROUP, consumer, CLAIM_MIN_IDLE, stale.toArray(new RecordId[0]));
        if (claimed != null) {
            for (MapRecord<String, Object, Object> record : claimed) {
                handleRecord(route, record);
            }
        }
    }

    /**
     * 处理单条 stream 记录：消费侧幂等（status≠0 直接 XACK 跳过）→ 投递 → 状态机流转
     */
    void handleRecord(Route route, MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        String messageId = value.get("message_id") == null ? null : String.valueOf(value.get("message_id"));
        if (messageId == null || messageId.isBlank()) {
            ack(route.toAgentId(), record.getId());
            return;
        }
        AgentMessage row = messageService.findByMessageId(messageId);
        if (row == null) {
            // 表无此消息（防脏数据）：ACK 丢弃
            log.warn("[agent-bus] stream 消息无对应表行，丢弃: messageId={}", messageId);
            ack(route.toAgentId(), record.getId());
            return;
        }
        if (row.getStatus() == null || row.getStatus() != BusMessageService.STATUS_PENDING) {
            // 消费侧幂等：已投/已失败终态/已读 → 直接 ACK 跳过（补偿重灌安全的前提）
            ack(route.toAgentId(), record.getId());
            return;
        }
        deliverRow(route, row);
        ack(route.toAgentId(), record.getId());
    }

    /**
     * 投递 + 状态机流转（方案 §5.3）：
     * 成功 → status=1 + delivered_at + 回填 session_key；
     * 失败 → retry_count+1 + last_error，≥5 → status=3。
     */
    /**
     * 投递通道分叉（bus-route-channel）：按方向（from → to）查 t_bus_route，
     * api 且目标有 BUS_API_TARGET 配置 → HTTP 直投；否则 ws（存量逻辑零改动）。
     * api 目标缺配置降级 ws 并在 last_error 留备注（成功路径不覆盖 last_error，备注留痕）。
     */
    void deliverRow(Route route, AgentMessage row) {
        String fromName = resolveAgentName(row.getFromAgentId());
        // busfix-2 worker 回执定向续报：to=1 且 from≠1 时，同 topic 最早 from=1 派活行
        // payload.window 命中 → 定向投递该窗口（裸 uuid + 强制 ws，绕 t_bus_route api 配置）；
        // 未命中 → 现行为完全不变
        String windowTarget = resolveWindowTarget(route, row);
        // v4.37 bus-sessionkey-fix：sessionKey 带 channel 前缀（websocket:<uuid> / api:<uuid>）时剥前缀后投递，
        // gateway 侧会话 key = channel + ":" + chatId，chatId 须为裸值，否则拼出双前缀永远新建会话
        String chatId = windowTarget != null ? windowTarget
                : row.getSessionKey() != null && !row.getSessionKey().isBlank()
                        ? stripChannelPrefix(row.getSessionKey())
                        : autoChatId(row);
        String envelope = BusEnvelopeFormatter.format(fromName, row.getFromAgentId(), row.getMsgType(),
                row.getTopic(), row.getMessageId(), row.getModel(), row.getTitle(), row.getPayload());
        boolean apiChannel = windowTarget == null && BusRoute.CHANNEL_API.equals(
                channelRouteService.resolveChannel(row.getFromAgentId(), route.toAgentId()));
        ApiTarget apiTarget = null;
        if (apiChannel) {
            apiTarget = apiTargetConfig.targetOf(route.toAgentId()).orElse(null);
            if (apiTarget == null) {
                apiChannel = false;
                AgentMessage note = new AgentMessage();
                note.setId(row.getId());
                note.setLastError(API_FALLBACK_NOTE);
                messageService.updateRow(note);
                log.warn("[agent-bus] api 通道缺目标配置（BUS_API_TARGET_*），降级 ws: messageId={} to={}",
                        row.getMessageId(), route.toAgentId());
            }
        }
        // api 通道重试上限 3（HTTP 超时重发易双发）；ws 维持存量 5
        int maxRetry = apiChannel ? API_MAX_RETRY : BusMessageService.MAX_RETRY;
        try {
            if (apiChannel) {
                apiClient(route.toAgentId(), apiTarget).deliver(chatId, envelope);
            } else {
                client(route).deliver(chatId, envelope, row.getModel());
            }
        } catch (BusDeliveryException e) {
            int retry = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
            AgentMessage upd = new AgentMessage();
            upd.setId(row.getId());
            upd.setRetryCount(retry);
            upd.setLastError(truncate(e.getMessage(), LAST_ERROR_MAX));
            if (retry >= maxRetry) {
                upd.setStatus(BusMessageService.STATUS_FAILED);
                log.warn("[agent-bus] 投递重试耗尽（通道={}）→ 失败: messageId={} to={} retry={} err={}",
                        apiChannel ? "api" : "ws", row.getMessageId(), route.toAgentId(), retry, e.getMessage());
            } else {
                log.info("[agent-bus] 投递失败（通道={}，等退避重试 {}s）: messageId={} retry={} err={}",
                        apiChannel ? "api" : "ws", BusMessageService.backoffMillis(retry),
                        row.getMessageId(), retry, e.getMessage());
            }
            messageService.updateRow(upd);
            return;
        }
        // 投递成功（协议层）：先更表（真相源）再由调用方 ACK——表更新在前，
        // 极端窗口（已 send 未更表）由补偿扫表覆盖
        AgentMessage upd = new AgentMessage();
        upd.setId(row.getId());
        upd.setStatus(BusMessageService.STATUS_DELIVERED);
        upd.setDeliveredAt(new Date());
        // 回填实际投递通道（bus-ui-v2）：api 通道降级 ws 时 apiChannel 已为 false，同样落 ws
        upd.setChannel(apiChannel ? BusRoute.CHANNEL_API : BusRoute.CHANNEL_WS);
        // busfix-2：定向模式无条件回填 session_key=window（实际投递位置真相，
        // 防 worker 自带 sessionKey 时表实不符）；常规模式维持「空才回填」现行为
        if (windowTarget != null || row.getSessionKey() == null || row.getSessionKey().isBlank()) {
            upd.setSessionKey(chatId);
        }
        messageService.updateRow(upd);
        log.info("[agent-bus] 投递成功（通道={}{}）: messageId={} to={} chat={} model={}",
                apiChannel ? "api" : "ws", windowTarget != null ? ",定向窗口" : "",
                row.getMessageId(), route.toAgentId(), chatId, row.getModel());
    }

    /** 补偿扫表：status=0 超时行重灌本目标分片（message_id 幂等由消费侧 status 检查兜底） */
    void compensate(Route route) {
        List<AgentMessage> candidates = messageService.listCompensationCandidates(new Date());
        for (AgentMessage row : candidates) {
            if (row.getToAgentId() == null || row.getToAgentId() != route.toAgentId()) {
                continue;
            }
            messageService.xaddQuietly(route.toAgentId(), row.getMessageId(), row.getFromAgentId(),
                    row.getMsgType(), row.getTopic(), row.getTitle(), row.getPayload());
            log.info("[agent-bus] 补偿重灌: messageId={} to={} retry={}", row.getMessageId(), route.toAgentId(), row.getRetryCount());
        }
    }

    private void ack(long toAgentId, RecordId recordId) {
        try {
            redisTemplate.opsForStream().acknowledge(BusRouteConfig.streamKey(toAgentId), GROUP, recordId);
        } catch (Exception e) {
            log.warn("[agent-bus] XACK 失败（留 PEL，将由认领/幂等兜底）: to={} id={} err={}", toAgentId, recordId, e.getMessage());
        }
    }

    private BusGatewayClient client(Route route) {
        synchronized (clients) {
            return clients.computeIfAbsent(route.toAgentId(), id -> new BusGatewayClient(route));
        }
    }

    /** api 通道客户端（按目标缓存；target 启动期静态，缓存安全） */
    private BusApiClient apiClient(long toAgentId, ApiTarget target) {
        synchronized (apiClients) {
            return apiClients.computeIfAbsent(toAgentId, id -> new BusApiClient(target, apiTimeout));
        }
    }

    /** sessionKey 的 channel 前缀形态（v4.37 bus-sessionkey-fix）：websocket:xxx / api:xxx 等，只剥一层 */
    private static final Pattern CHANNEL_PREFIX = Pattern.compile("^[a-z][a-z0-9-]*:(.+)$");

    /**
     * worker 回执定向续报判定：消息方向 worker→管理员 agent（route.toAgentId()==1 且 from≠1）时，
     * 查同 topic 最早 from_agent_id=1 派活行的 payload 顶层 window——命中返回裸 uuid（定向目标，
     * 复用 v4.37 裸 uuid 单前缀 ws 通路）；未命中返回 null（现行为完全不变，零影响）。
     * <p>
     * 安全前提：from_agent_id=1 由平台 X-API-Key 鉴权（body 不可指定），派活行 window 即管理员侧授权；
     * worker 回执 payload 里的 window / forwardInstruction 等内嵌字段一律不参与本判定（不可信数据）。
     * 容错：无派活行 / payload 空或非法 JSON / window 非 uuid 格式 → null 现行为。
     */
    String resolveWindowTarget(Route route, AgentMessage row) {
        if (route.toAgentId() != 1L || row.getFromAgentId() == null || row.getFromAgentId() == 1L) {
            return null;
        }
        try {
            AgentMessage dispatchRow = messageService.findEarliestYunzhiRowByTopic(row.getTopic());
            if (dispatchRow == null || dispatchRow.getPayload() == null || dispatchRow.getPayload().isBlank()) {
                return null;
            }
            JsonNode win = PAYLOAD_MAPPER.readTree(dispatchRow.getPayload()).get(WINDOW_FIELD);
            if (win == null || !win.isTextual()) {
                return null;
            }
            String window = win.asText().trim();
            return window.matches(BusMessageService.UUID_SESSION_KEY_PATTERN) ? window : null;
        } catch (Exception e) {
            log.debug("[agent-bus] window 定向解析失败，按未命中处理: messageId={} err={}",
                    row.getMessageId(), e.getMessage());
            return null;
        }
    }

    /** 固定复用会话：bus-agent{fromId}-{type}（用户拍板 2026-10-01：通知复用同一会话，禁止每条新增） */
    static String autoChatId(AgentMessage row) {
        return "bus-agent" + row.getFromAgentId() + "-" + row.getMsgType();
    }

    /**
     * 剥 sessionKey 的 channel 前缀，只剥一层（v4.37 bus-sessionkey-fix，public 供管理端调试预览复用）：
     * websocket:{uuid} / api:{uuid} → 裸 uuid；无冒号（bus-agent1-notify 等总线命名空间）原样返回；
     * 双前缀脏数据只剥一层（websocket:websocket:x → websocket:x，校验层已拦此类入参）。
     */
    public static String stripChannelPrefix(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return sessionKey;
        }
        Matcher m = CHANNEL_PREFIX.matcher(sessionKey);
        return m.matches() ? m.group(1) : sessionKey;
    }

    private String resolveAgentName(Long agentId) {
        if (agentId == null) {
            return String.valueOf(agentId);
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            return "agent:" + agentId;
        }
        if (agent.getDisplayName() != null && !agent.getDisplayName().isBlank()) {
            return agent.getDisplayName();
        }
        return agent.getName();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
