package com.youlxb2008.admin.modules.bus.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.exceptions.TooManyRequestsException;
import com.youlxb2008.admin.exceptions.UnprocessableEntityException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.dto.BusManualSendReq;
import com.youlxb2008.admin.modules.bus.dto.BusMessageCreateReq;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.vo.BusDeliveryTargetVO;
import com.youlxb2008.admin.modules.bus.vo.BusMessageVO;
import com.youlxb2008.admin.modules.bus.vo.BusRouteStatVO;
import com.youlxb2008.admin.modules.bus.vo.BusStatsVO;
import com.youlxb2008.admin.modules.bus.vo.PortalBusMessageVO;
import com.youlxb2008.admin.modules.bus.vo.PortalBusStatsVO;
import com.youlxb2008.admin.utils.page.BPage;

import lombok.extern.slf4j.Slf4j;

/**
 * 消息总线核心服务（M1）
 * <p>
 * 职责：
 * <ol>
 *   <li>生产校验链（方案 §4.1，顺序即代码顺序）：to_agent 存在且启用 → type 白名单（request 422）
 *       → 频率双限（超限 429）→ payload ≤8KB → sessionKey bus-* 命名空间 → model 白名单
 *       → INSERT(status=0) → 事务提交后 XADD 目标分片 → 返回 messageId</li>
 *   <li>from 身份安全：生产 API 的 from 由调用方从 X-API-Key 推导传入（AgentContext），
 *       DTO 无 fromAgentId 字段，body 伪造无效；管理端手动发消息可任选 from（演练/e2e），走同一校验链</li>
 *   <li>管理端查询（列表/详情/统计）与手动重投（status∈{0,3}，重入 XADD）</li>
 * </ol>
 * XADD 时机：事务提交后（afterCommit）——dispatcher 消费侧查表须能看到已提交行；
 * Redis 不可用时降级放行（消息已落表，dispatcher 补偿扫表 60s 内重灌，at-least-once 语义兜底）。
 */
@Slf4j
@Service
public class BusMessageService {

    /** 类型白名单（M1；request 分级禁用 → 422） */
    public static final Set<String> ALLOWED_TYPES = Set.of("notify", "alert");
    /** 禁用类型（明确提示语义） */
    public static final String TYPE_REQUEST = "request";
    /** 需正文的类型（busfix-1007：notify/request；alert 标题即正文，不参与空 payload 告警） */
    public static final Set<String> CONTENT_REQUIRED_TYPES = Set.of("notify", TYPE_REQUEST);
    /** payload.content 空白的入队告警文案（busfix-1007；照常入队不阻断，附在响应 data.warning） */
    public static final String PAYLOAD_EMPTY_WARNING = "payload 为空，接收方将看不到正文";
    /** payload 上限：8KB（序列化后字节数） */
    public static final int PAYLOAD_MAX_BYTES = 8 * 1024;
    /** sessionKey 命名空间：bus-* 强制（禁注入收件人既有会话） */
    public static final String SESSION_KEY_PATTERN = "^bus-[A-Za-z0-9_:-]{1,56}$";
    /**
     * 定向落点形态①（v4.37 bus-sessionkey-fix 放开）：裸 uuid（WebUI 聊天会话 id），
     * dispatcher 剥前缀逻辑对无冒号值原样透传，投进该会话窗口。
     */
    public static final String UUID_SESSION_KEY_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    /**
     * 定向落点形态②（v4.37 bus-sessionkey-fix 放开）：channel 前缀 + uuid，
     * websocket:&lt;uuid&gt; / api:&lt;uuid&gt;（dispatcher 投递前剥前缀，gateway 会话 key = channel:chatId）。
     * 取代原 ^websocket:[A-Za-z0-9][A-Za-z0-9_-]{0,63}$（bus-watchdog-fuse ②）：
     * 标识收紧为 uuid 格式（uuid 即真实会话 id，自由字符串无落点语义且易误投新建会话）。
     */
    public static final String CHANNEL_UUID_SESSION_KEY_PATTERN =
            "^(?:websocket|api):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
    /** trusted agents env key（bus.trusted-agents ← env BUS_TRUSTED_AGENTS；空/缺省=1） */
    private static final String TRUSTED_AGENTS_KEY = "bus.trusted-agents";
    /** trusted agents 缺省值（初始=1 管理员 agent；值 0=显式清空，真实 agent id 从 1 起） */
    private static final String TRUSTED_AGENTS_DEFAULT = "1";
    /** 状态：待投递 / 已送达（协议层）/ 已读（M2）/ 失败 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_DELIVERED = 1;
    public static final int STATUS_READ = 2;
    public static final int STATUS_FAILED = 3;
    /** 投递重试上限（指数退避耗尽 → status=3） */
    public static final int MAX_RETRY = 5;
    /** 指数退避序列（秒）：1s/2s/4s/8s/16s */
    public static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16};
    /** 消息级回音时限下界（分钟，bus-watchdog-fuse）；0 = 显式无需回音、不参与熔断（bus-fuse-zero-optout） */
    public static final int EXPECT_REPLY_MIN_MINUTES = 0;
    /** 消息级回音时限上界（分钟 = 24h） */
    public static final int EXPECT_REPLY_MAX_MINUTES = 1440;

    private final AgentMessageMapper messageMapper;
    private final AgentMapper agentMapper;
    private final BusRateLimiter rateLimiter;
    private final BusRouteConfig routeConfig;
    private final BusApiTargetConfig apiTargetConfig;
    private final StringRedisTemplate redisTemplate;
    /** sessionKey 守卫白名单（bus-watchdog-fuse ②；from ∈ 白名单可携 websocket: 落点） */
    private final Set<Long> trustedAgents;

    /**
     * 入队结果（busfix-1007）：messageId + 可空 warning
     * （warning 仅 notify/request 且最终 payload.content 空白时非 null，照常入队不影响 201）。
     */
    public record BusSubmitResult(String messageId, String warning) {
    }

    public BusMessageService(AgentMessageMapper messageMapper, AgentMapper agentMapper,
            BusRateLimiter rateLimiter, BusRouteConfig routeConfig,
            BusApiTargetConfig apiTargetConfig,
            ObjectProvider<StringRedisTemplate> redisProvider, Environment environment) {
        this.messageMapper = messageMapper;
        this.agentMapper = agentMapper;
        this.rateLimiter = rateLimiter;
        this.routeConfig = routeConfig;
        this.apiTargetConfig = apiTargetConfig;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.trustedAgents = parseTrustedAgents(
                environment.getProperty(TRUSTED_AGENTS_KEY, TRUSTED_AGENTS_DEFAULT));
    }

    /**
     * 解析白名单：逗号分隔 agent id（ relaxed binding 命中 env BUS_TRUSTED_AGENTS）；
     * 非法片段忽略；解析结果空且原文非「0」→ 回退缺省 1（防手滑清空守卫面）。
     */
    private static Set<Long> parseTrustedAgents(String raw) {
        Set<Long> ids = new HashSet<>();
        String src = raw == null || raw.isBlank() ? TRUSTED_AGENTS_DEFAULT : raw.trim();
        for (String piece : src.split(",")) {
            try {
                ids.add(Long.parseLong(piece.trim()));
            } catch (NumberFormatException ignored) {
                // 非法片段忽略（日志见构造器汇总）
            }
        }
        if (ids.isEmpty() && !"0".equals(src)) {
            ids.add(1L);
        }
        return ids;
    }

    /** from 是否在 sessionKey 守卫白名单（②；包级供测试） */
    boolean isTrustedAgent(long fromAgentId) {
        return trustedAgents.contains(fromAgentId);
    }

    // ==================== 生产 API（from 由 X-API-Key 推导） ====================

    /**
     * 生产端发消息（校验链顺序即代码顺序，方案 §4.1）
     *
     * @param fromAgentId 生产者（由 X-API-Key 推导，body 不可指定）
     * @return messageId + 可空 warning（busfix-1007）
     */
    public BusSubmitResult sendFromKey(long fromAgentId, BusMessageCreateReq req) {
        return submit(fromAgentId, req.getToAgentId(), req.getType(), req.getTopic(), req.getTitle(),
                req.getPayload(), req.getContent(), req.getSessionKey(), req.getModel(),
                req.getExpectReplyMinutes());
    }

    // ==================== 管理端手动发消息（可任选 from，同一校验链） ====================

    /**
     * 管理端手动发消息（演练 / e2e 工具；频控按所选 from 照常）
     */
    public BusSubmitResult sendManual(BusManualSendReq req) {
        return submit(req.getFromAgentId(), req.getToAgentId(), req.getType(), req.getTopic(), req.getTitle(),
                req.getPayload(), req.getContent(), req.getSessionKey(), req.getModel(),
                req.getExpectReplyMinutes());
    }

    // ==================== 校验链 + 入库 + 入队 ====================

    /**
     * 校验链（方案 §4.1 十步中服务端负责的八步 + busfix-1007 两步）：
     * 顶层 content 兼容映射 → to_agent 存在且启用 → type 白名单 → 频控双限 → payload ≤8KB
     * → sessionKey 命名空间（bus-*；白名单 agent 另可 websocket:，bus-watchdog-fuse ②）
     * → expectReplyMinutes 0（无需回音）或 1~1440 → model 白名单（含目标路由存在性）
     * → INSERT(status=0) → XADD → 空 payload 告警（notify/request）。
     */
    private BusSubmitResult submit(long fromAgentId, Long toAgentId, String type, String topic, String title,
            Object payload, String content, String sessionKey, String model, Integer expectReplyMinutes) {
        // 0. 顶层 content 兼容映射（busfix-1007）：payload 空（null/空白串）且 content 非 blank
        //    → payload={"content": content}；payload 已有值时忽略 content（不合并）。
        //    背景：Spring 对请求体未知字段静默忽略，生产端误用顶层 content 曾致正文丢失（2026-10-07）
        if ((payload == null || (payload instanceof String ps && ps.isBlank()))
                && content != null && !content.isBlank()) {
            payload = Map.of("content", content);
        }
        // 1. to_agent 存在且启用
        Agent toAgent = agentMapper.selectById(toAgentId);
        if (toAgent == null || (toAgent.getStatus() == null || toAgent.getStatus() != 1)) {
            throw new UnprocessableEntityException("目标 agent 不存在或未启用: " + toAgentId);
        }
        // 2. type 白名单（request M1 直接 422，总线只告知不指挥）
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            String hint = TYPE_REQUEST.equals(type)
                    ? "类型 request 在 M1 禁用（总线只告知不指挥）"
                    : "类型须为 notify/alert";
            throw new UnprocessableEntityException(hint);
        }
        // 3. 频率双限（分钟 ≤600 + 日 ≤10000，超限 429；Redis 不可用放行降级）
        if (!rateLimiter.tryAcquire(fromAgentId)) {
            throw new TooManyRequestsException("发送频率超限（每生产者 ≤" + BusRateLimiter.MINUTE_LIMIT
                    + " 条/分钟且 ≤" + BusRateLimiter.DAILY_LIMIT + " 条/天）");
        }
        // 4. payload ≤8KB（序列化后计字节数）
        String payloadJson;
        try {
            payloadJson = BusEnvelopeFormatter.serializePayload(payload);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableEntityException(e.getMessage());
        }
        if (payloadJson != null
                && payloadJson.getBytes(StandardCharsets.UTF_8).length > PAYLOAD_MAX_BYTES) {
            throw new UnprocessableEntityException("payload 超限（≤8KB）");
        }
        // 5. sessionKey 命名空间（空 = dispatcher 复用固定会话 bus-agent{fromId}-{type}；
        //    白名单 agent（BUS_TRUSTED_AGENTS，from 由 API key 鉴定 body 不可伪造）另可携定向落点：
        //    ①裸 uuid ②websocket:<uuid> / api:<uuid>（v4.37 bus-sessionkey-fix），
        //    dispatcher 剥前缀后投递，保证落进既有会话窗口；非白名单维持 bus-* 守卫不变）
        if (sessionKey != null && !sessionKey.isBlank()) {
            boolean busOk = sessionKey.matches(SESSION_KEY_PATTERN);
            boolean directedOk = isTrustedAgent(fromAgentId)
                    && (sessionKey.matches(UUID_SESSION_KEY_PATTERN)
                            || sessionKey.matches(CHANNEL_UUID_SESSION_KEY_PATTERN));
            if (!busOk && !directedOk) {
                throw new UnprocessableEntityException("sessionKey 须为 bus-* 命名空间"
                        + "（^bus-[A-Za-z0-9_:-]{1,56}$），白名单 agent 另可用裸 uuid /"
                        + " websocket:<uuid> / api:<uuid> 定向落点（uuid 格式校验，dispatcher 剥前缀后投递）；"
                        + "禁指定收件人既有非定向会话");
            }
        }
        // 5.5 消息级回音时限（bus-watchdog-fuse ①；空 = 走全局默认 BUS_FUSE_DEFAULT_MINUTES；
        //     0 = 显式无需回音、不参与熔断（bus-fuse-zero-optout），全局默认不允许 0）
        if (expectReplyMinutes != null
                && (expectReplyMinutes < EXPECT_REPLY_MIN_MINUTES
                        || expectReplyMinutes > EXPECT_REPLY_MAX_MINUTES)) {
            throw new UnprocessableEntityException("expectReplyMinutes 须为 0（无需回音）或 1~"
                    + EXPECT_REPLY_MAX_MINUTES + " 分钟");
        }
        // 6. model 白名单（对照目标路由行 models；目标未接入总线一并 422）
        if (!routeConfig.hasRoute(toAgentId)) {
            throw new UnprocessableEntityException("目标 agent 未接入消息总线（无路由配置）: " + toAgentId);
        }
        if (model != null && !model.isBlank() && !routeConfig.modelAllowed(toAgentId, model)) {
            throw new UnprocessableEntityException("model 不在目标 agent 白名单内: " + model);
        }
        // 7. INSERT(status=0)（messageId = UUID 幂等键）
        AgentMessage row = new AgentMessage();
        row.setMessageId(UUID.randomUUID().toString());
        row.setFromAgentId(fromAgentId);
        row.setToAgentId(toAgentId);
        row.setMsgType(type);
        row.setTopic(topic);
        row.setTitle(title);
        row.setPayload(payloadJson);
        row.setSessionKey(sessionKey == null || sessionKey.isBlank() ? null : sessionKey);
        row.setModel(model == null || model.isBlank() ? null : model);
        row.setExpectReplyMinutes(expectReplyMinutes);
        row.setStatus(STATUS_PENDING);
        row.setRetryCount(0);
        messageMapper.insert(row);
        // 8. XADD 目标分片（事务提交后执行；失败降级——表为真相源，补偿扫表会重灌）
        final long toId = toAgentId;
        final String messageId = row.getMessageId();
        registerAfterCommitXadd(toId, messageId, fromAgentId, type, topic, title, payloadJson);
        // 9. 入队告警（busfix-1007）：notify/request 且最终 payload.content 空白 → 照常入队，
        //    响应附 warning 提示（不阻断、不改状态；alert 标题即正文不提示）
        String warning = null;
        if (CONTENT_REQUIRED_TYPES.contains(type)
                && BusEnvelopeFormatter.isContentBlank(payloadJson)) {
            warning = PAYLOAD_EMPTY_WARNING;
        }
        return new BusSubmitResult(messageId, warning);
    }

    /** 事务提交后 XADD（@Rollback 的测试/事务回滚场景不投递） */
    private void registerAfterCommitXadd(long toAgentId, String messageId, long fromAgentId,
            String type, String topic, String title, String payloadJson) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            xaddQuietly(toAgentId, messageId, fromAgentId, type, topic, title, payloadJson);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                xaddQuietly(toAgentId, messageId, fromAgentId, type, topic, title, payloadJson);
            }
        });
    }

    /**
     * XADD 到目标分片 stream（字段按方案 §3.2；dispatcher 消费以表为真相源）。
     * Redis 不可用/异常：log warn 后返回（不向上抛，201 契约不受 Redis 瞬时故障影响）。
     */
    public void xaddQuietly(long toAgentId, String messageId, long fromAgentId,
            String type, String topic, String title, String payloadJson) {
        if (redisTemplate == null) {
            log.warn("[agent-bus] Redis 不可用，消息 {} 暂不入队（补偿扫表将重灌）: to={}", messageId, toAgentId);
            return;
        }
        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("message_id", messageId);
            fields.put("from", String.valueOf(fromAgentId));
            fields.put("to", String.valueOf(toAgentId));
            fields.put("type", type == null ? "" : type);
            fields.put("topic", topic == null ? "" : topic);
            fields.put("title", title == null ? "" : title);
            fields.put("payload", payloadJson == null ? "" : payloadJson);
            fields.put("ts", String.valueOf(System.currentTimeMillis()));
            redisTemplate.opsForStream().add(BusRouteConfig.streamKey(toAgentId), fields);
        } catch (Exception e) {
            log.warn("[agent-bus] XADD 失败（补偿扫表将重灌）: messageId={} to={} err={}", messageId, toAgentId, e.getMessage());
        }
    }

    // ==================== 管理端：手动重投 ====================

    /**
     * 手动重投（status∈{0,3} 可用；status=3 重置为待投递并清零重试计数，重入 XADD）
     */
    @Transactional
    public void retry(Long id) {
        AgentMessage row = messageMapper.selectById(id);
        if (row == null) {
            throw new NotFoundException("消息不存在: " + id);
        }
        Integer status = row.getStatus();
        if (status == null || (status != STATUS_PENDING && status != STATUS_FAILED)) {
            throw new BadRequestException("仅待投递(0)/失败(3)状态可重投，当前: " + status);
        }
        if (status == STATUS_FAILED) {
            row.setStatus(STATUS_PENDING);
            row.setRetryCount(0);
            messageMapper.updateById(row);
        }
        // status=0：表无变更，直接重灌队列（幂等，消费侧 status 幂等兜底）
        registerAfterCommitXadd(row.getToAgentId(), row.getMessageId(), row.getFromAgentId(),
                row.getMsgType(), row.getTopic(), row.getTitle(), row.getPayload());
    }

    // ==================== 管理端：列表 / 详情 / 统计 ====================

    /**
     * 分页列表（筛选：来源/目标/type/status；agent id 解析名称展示）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public BPage page(Long fromAgentId, Long toAgentId, Integer status, String type,
            long page, long size) {
        long normSize = Math.min(Math.max(size, 1), 100);
        Page<AgentMessage> pg = messageMapper.selectPage(
                new Page<>(Math.max(page, 1), normSize),
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(fromAgentId != null, AgentMessage::getFromAgentId, fromAgentId)
                        .eq(toAgentId != null, AgentMessage::getToAgentId, toAgentId)
                        .eq(status != null, AgentMessage::getStatus, status)
                        .eq(type != null && !type.isBlank(), AgentMessage::getMsgType, type)
                        .orderByDesc(AgentMessage::getId));
        Map<Long, String> nameMap = agentNames(collectAgentIds(pg.getRecords()));
        List<BusMessageVO> vos = pg.getRecords().stream()
                .map(row -> toVO(row, nameMap))
                .collect(Collectors.toList());
        BPage bp = BPage.of(pg);
        bp.setList(vos);
        return bp;
    }

    /** 详情（完整 payload + session/model + last_error） */
    public BusMessageVO detail(Long id) {
        AgentMessage row = messageMapper.selectById(id);
        if (row == null) {
            throw new NotFoundException("消息不存在: " + id);
        }
        Map<Long, String> nameMap = agentNames(Set.of(row.getFromAgentId(), row.getToAgentId()));
        return toVO(row, nameMap);
    }

    /** 统计（今日进线 / 待投积压 / 失败积压 / 平均投递耗时） */
    public BusStatsVO stats(int days) {
        BusStatsVO vo = new BusStatsVO();
        vo.setDays(days);
        Date now = new Date();
        Date todayStart = todayStart(now);
        vo.setTodayTotal(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .ge(AgentMessage::getCreateTime, todayStart)));
        vo.setPendingCount(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getStatus, STATUS_PENDING)));
        vo.setFailedCount(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getStatus, STATUS_FAILED)));
        Date windowStart = new Date(now.getTime() - days * 24L * 3600L * 1000L);
        List<AgentMessage> delivered = messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .select(AgentMessage::getDeliveredAt, AgentMessage::getCreateTime)
                .eq(AgentMessage::getStatus, STATUS_DELIVERED)
                .ge(AgentMessage::getCreateTime, windowStart)
                .isNotNull(AgentMessage::getDeliveredAt));
        if (!delivered.isEmpty()) {
            double totalSeconds = 0;
            int counted = 0;
            for (AgentMessage row : delivered) {
                if (row.getDeliveredAt() == null || row.getCreateTime() == null) {
                    continue;
                }
                long diff = row.getDeliveredAt().getTime() - row.getCreateTime().getTime();
                if (diff < 0) {
                    continue;
                }
                totalSeconds += diff / 1000.0;
                counted++;
            }
            vo.setAvgDeliverySeconds(counted > 0 ? round2(totalSeconds / counted) : null);
        }
        return vo;
    }

    // ==================== 门户只读（M1 门户包，匿名公开域） ====================

    /**
     * 门户消息流转分页（匿名只读；id 倒序；筛选 from/to/status/type/topic/days/keyword 可空）。
     * VO 走 {@link PortalBusMessageVO} 白名单，绝不返回 payload/sessionKey/model/lastError
     * （portal 匿名域防内部内容泄漏）；size 钳制 ≤100（任务书 §1）。
     * <p>
     * bus-ui-v2 扩参：topic（精确）、days（近 N 天，create_time &gt;= now-N*24h；缺省/0=不过滤）、
     * keyword（title/topic LIKE 模糊，反斜杠转义 %/_/\ 防通配符注入）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public BPage portalPage(Long fromAgentId, Long toAgentId, Integer status, String type,
            String topic, Integer days, String keyword, String channel, long page, long size) {
        long normSize = Math.min(Math.max(size, 1), 100);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<AgentMessage> pg = messageMapper.selectPage(
                new Page<>(Math.max(page, 1), normSize),
                portalFilter(fromAgentId, toAgentId, status, type, topic, days, kw, channel)
                        .orderByDesc(AgentMessage::getId));
        Map<Long, String> nameMap = agentNames(collectAgentIds(pg.getRecords()));
        List<PortalBusMessageVO> vos = pg.getRecords().stream()
                .map(row -> toPortalVO(row, nameMap))
                .collect(Collectors.toList());
        BPage bp = BPage.of(pg);
        bp.setList(vos);
        return bp;
    }

    /**
     * 门户/管理端共用的列表过滤条件（bus-ui-v2：加 topic/days/keyword 三参；
     * keyword LIKE 转义后经 {0} 预编译占位（默认反斜杠转义符，H2 MODE=MySQL 与 MySQL 一致））
     */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessage> portalFilter(
            Long fromAgentId, Long toAgentId, Integer status, String type,
            String topic, Integer days, String keyword, String channel) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessage> wrapper =
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(fromAgentId != null, AgentMessage::getFromAgentId, fromAgentId)
                        .eq(toAgentId != null, AgentMessage::getToAgentId, toAgentId)
                        .eq(status != null, AgentMessage::getStatus, status)
                        .eq(type != null && !type.isBlank(), AgentMessage::getMsgType, type)
                        .eq(topic != null && !topic.isBlank(), AgentMessage::getTopic, topic)
                        .eq(channel != null && !channel.isBlank(), AgentMessage::getChannel, channel);
        if (days != null && days > 0) {
            wrapper.ge(AgentMessage::getCreateTime,
                    new Date(System.currentTimeMillis() - Math.min(days, 366) * 24L * 3600L * 1000L));
        }
        if (keyword != null) {
            String escaped = escapeLike(keyword);
            // 转义依赖默认转义符反斜杠（前置项目同款口径）：H2 MODE=MySQL 与 MySQL 默认一致；
            // 不用显式 ESCAPE 子句（H2 字面量反斜杠与 MySQL 字符串转义语义不同，兼容性炸弹）
            wrapper.apply("(title LIKE CONCAT('%', {0}, '%') OR topic LIKE CONCAT('%', {1}, '%'))",
                    escaped, escaped);
        }
        return wrapper;
    }

    /** LIKE 通配符转义（\ % _ 前置反斜杠；依赖 DB 默认反斜杠转义符） */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * 门户统计（/bus 页顶部胶囊）：今日进线 / 已投递(status=1) / 待投(status=0) /
     * 失败(其余状态合计)。除「今日」外均为全时段口径。
     * <p>
     * bus-ui-v2 追加：hotTopics（热门主题 top6）与 topics（全部主题计数，筛选下拉数据源），
     * 全时段计数，拉 topic 列 Java TreeMap 分组（v4.18 活动统计同款，H2/MySQL 通用）。
     */
    public PortalBusStatsVO portalStats() {
        PortalBusStatsVO vo = new PortalBusStatsVO();
        Date todayStart = todayStart(new Date());
        vo.setTodayTotal(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .ge(AgentMessage::getCreateTime, todayStart)));
        vo.setDelivered(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getStatus, STATUS_DELIVERED)));
        vo.setPending(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getStatus, STATUS_PENDING)));
        vo.setFailed(messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
                .notIn(AgentMessage::getStatus, List.of(STATUS_PENDING, STATUS_DELIVERED))));
        List<PortalBusStatsVO.TopicCount> topics = topicCounts();
        vo.setTopics(topics);
        vo.setHotTopics(topics.subList(0, Math.min(HOT_TOPICS_LIMIT, topics.size())));
        return vo;
    }

    /** hotTopics 截取条数（任务书：top6） */
    private static final int HOT_TOPICS_LIMIT = 6;

    /**
     * 全部主题计数（全时段）：拉 topic 列 Java 分组，count 降序、同数按 topic 升序；
     * 空 topic 归入「(空)」不参与展示筛选（空 topic 消息极少，前端下拉不供空项）。
     */
    private List<PortalBusStatsVO.TopicCount> topicCounts() {
        List<AgentMessage> rows = messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .select(AgentMessage::getTopic));
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (AgentMessage row : rows) {
            String topic = row.getTopic();
            if (topic == null || topic.isBlank()) {
                continue;
            }
            counts.merge(topic, 1L, Long::sum);
        }
        List<PortalBusStatsVO.TopicCount> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            PortalBusStatsVO.TopicCount tc = new PortalBusStatsVO.TopicCount();
            tc.setTopic(e.getKey());
            tc.setCount(e.getValue());
            out.add(tc);
        }
        out.sort((a, b) -> {
            int c = Long.compare(b.getCount(), a.getCount());
            return c != 0 ? c : a.getTopic().compareTo(b.getTopic());
        });
        return out;
    }

    // ==================== 管理端：投递通道可用性 / 方向计数（bus-ui-v2） ====================

    /**
     * 投递通道可用性（GET /system/bus/delivery-targets）：全部 agent 逐个解析
     * ws（BUS_TARGET_*）/ api（BUS_API_TARGET_*）配置；两项均 false = 无实例。
     * apiBaseUrl 仅 baseUrl 段（apiKey 绝不出域）。
     */
    public List<BusDeliveryTargetVO> deliveryTargets() {
        List<Agent> agents = agentMapper.selectList(Wrappers.<Agent>lambdaQuery().orderByAsc(Agent::getId));
        List<BusDeliveryTargetVO> out = new ArrayList<>(agents.size());
        for (Agent a : agents) {
            BusDeliveryTargetVO vo = new BusDeliveryTargetVO();
            vo.setAgentId(a.getId());
            vo.setWsConfigured(routeConfig.hasRoute(a.getId()));
            boolean apiConfigured = apiTargetConfig.hasTarget(a.getId());
            vo.setApiConfigured(apiConfigured);
            vo.setApiBaseUrl(apiConfigured ? apiTargetConfig.targetOf(a.getId()).map(
                    BusApiTargetConfig.ApiTarget::baseUrl).orElse(null) : null);
            out.add(vo);
        }
        return out;
    }

    /**
     * 方向近 24h 消息计数（GET /system/bus/route-stats）：拉 from/to/create_time 三列
     * Java 分组（H2/MySQL 通用），from/to 升序。
     */
    public List<BusRouteStatVO> routeStats24h() {
        Date since = new Date(System.currentTimeMillis() - 24L * 3600L * 1000L);
        List<AgentMessage> rows = messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .select(AgentMessage::getFromAgentId, AgentMessage::getToAgentId)
                .ge(AgentMessage::getCreateTime, since));
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (AgentMessage row : rows) {
            if (row.getFromAgentId() == null || row.getToAgentId() == null) {
                continue;
            }
            counts.merge(row.getFromAgentId() + ":" + row.getToAgentId(), 1L, Long::sum);
        }
        List<BusRouteStatVO> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            String[] parts = e.getKey().split(":", 2);
            BusRouteStatVO vo = new BusRouteStatVO();
            vo.setFromAgentId(Long.parseLong(parts[0]));
            vo.setToAgentId(Long.parseLong(parts[1]));
            vo.setCount24h(e.getValue());
            out.add(vo);
        }
        return out;
    }

    /** 索引行 → 门户白名单 VO（仅暴露展示字段，内部字段不出域） */
    private PortalBusMessageVO toPortalVO(AgentMessage row, Map<Long, String> nameMap) {
        PortalBusMessageVO vo = new PortalBusMessageVO();
        vo.setId(row.getId());
        vo.setFromAgentId(row.getFromAgentId());
        vo.setFromAgentName(nameMap.get(row.getFromAgentId()));
        vo.setToAgentId(row.getToAgentId());
        vo.setToAgentName(nameMap.get(row.getToAgentId()));
        vo.setMsgType(row.getMsgType());
        vo.setTopic(row.getTopic());
        vo.setTitle(row.getTitle());
        vo.setStatus(row.getStatus());
        vo.setChannel(row.getChannel());
        vo.setRetryCount(row.getRetryCount());
        vo.setDeliveredAt(row.getDeliveredAt());
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }

    // ==================== 私有工具 ====================

    private BusMessageVO toVO(AgentMessage row, Map<Long, String> nameMap) {
        BusMessageVO vo = new BusMessageVO();
        vo.setId(row.getId());
        vo.setMessageId(row.getMessageId());
        vo.setFromAgentId(row.getFromAgentId());
        vo.setFromAgentName(nameMap.get(row.getFromAgentId()));
        vo.setToAgentId(row.getToAgentId());
        vo.setToAgentName(nameMap.get(row.getToAgentId()));
        vo.setMsgType(row.getMsgType());
        vo.setTopic(row.getTopic());
        vo.setTitle(row.getTitle());
        vo.setPayload(row.getPayload());
        vo.setSessionKey(row.getSessionKey());
        vo.setModel(row.getModel());
        vo.setStatus(row.getStatus());
        vo.setRetryCount(row.getRetryCount());
        vo.setLastError(row.getLastError());
        vo.setDeliveredAt(row.getDeliveredAt());
        vo.setCreateTime(row.getCreateTime());
        vo.setUpdateTime(row.getUpdateTime());
        return vo;
    }

    private Set<Long> collectAgentIds(List<AgentMessage> rows) {
        Set<Long> ids = new java.util.HashSet<>();
        for (AgentMessage row : rows) {
            if (row.getFromAgentId() != null) {
                ids.add(row.getFromAgentId());
            }
            if (row.getToAgentId() != null) {
                ids.add(row.getToAgentId());
            }
        }
        return ids;
    }

    private Map<Long, String> agentNames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Agent> agents = agentMapper.selectBatchIds(ids);
        return agents.stream().collect(Collectors.toMap(Agent::getId, a -> {
            if (a.getDisplayName() != null && !a.getDisplayName().isBlank()) {
                return a.getDisplayName();
            }
            return a.getName();
        }, (a, b) -> a));
    }

    private Date todayStart(Date now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(now);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 供 dispatcher 复用的表访问（包内） */
    AgentMessageMapper mapper() {
        return messageMapper;
    }

    /** 定向更新（仅非 null 字段落库；dispatcher 状态机流转用） */
    public void updateRow(AgentMessage patch) {
        messageMapper.updateById(patch);
    }

	/** 按 message_id 查消息（dispatcher 消费侧幂等检查用） */
	public AgentMessage findByMessageId(String messageId) {
		return messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getMessageId, messageId)
				.last("LIMIT 1"));
	}

	/**
	 * busfix-2 worker 回执定向续报：查同 topic 最早 from_agent_id=1 派活行
	 * （from=1 由 agc X-API-Key 鉴权定，body 不可伪造；其 payload 顶层 window
	 * 即管理员侧授权的回执定向投递目标）。无派活行返回 null（dispatcher 现行为完全不变）。
	 */
	public AgentMessage findEarliestYunzhiRowByTopic(String topic) {
		return messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getTopic, topic)
				.eq(AgentMessage::getFromAgentId, 1L)
				.orderByAsc(AgentMessage::getId)
				.last("LIMIT 1"));
	}

    /** 补偿扫表：status=0 且超过重灌时点的行（dispatcher 每 60s 调用） */
    public List<AgentMessage> listCompensationCandidates(Date now) {
        List<AgentMessage> pending = messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getStatus, STATUS_PENDING)
                .lt(AgentMessage::getCreateTime, new Date(now.getTime() - 2L * 60L * 1000L))
                .orderByAsc(AgentMessage::getId));
        // 按 retry_count 推算退避窗：update_time + backoff(retryCount) 未到的跳过（指数退避下限）
        List<AgentMessage> result = new ArrayList<>();
        for (AgentMessage row : pending) {
            int retry = row.getRetryCount() == null ? 0 : row.getRetryCount();
            if (retry > 0) {
                Date base = row.getUpdateTime() != null ? row.getUpdateTime() : row.getCreateTime();
                long backoffMs = backoffMillis(retry) * 1000L;
                if (base != null && base.getTime() + backoffMs > now.getTime()) {
                    continue;
                }
            }
            result.add(row);
            if (result.size() >= 100) {
                break;
            }
        }
        return result;
    }

    /** 指数退避秒数（retryCount 从 1 起；越界取最后一档） */
    public static long backoffMillis(int retryCount) {
        if (retryCount <= 0) {
            return BACKOFF_SECONDS[0];
        }
        int idx = Math.min(retryCount - 1, BACKOFF_SECONDS.length - 1);
        return BACKOFF_SECONDS[idx];
    }
}
