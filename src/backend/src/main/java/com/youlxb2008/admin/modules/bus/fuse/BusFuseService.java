package com.youlxb2008.admin.modules.bus.fuse;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.env.Environment;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.modules.bus.dto.BusTopicRange;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusTopicWatch;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.mapper.BusTopicWatchMapper;
import com.youlxb2008.admin.modules.bus.service.BusEnvelopeFormatter;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 总线超时保险丝（bus-watchdog-fuse）
 * <p>
 * 背景：worker 卡死/失联时不发任何消息，由本组件定时扫描发现「等待回音超时」并告警发起人
 * （早期常驻 watchdog 退役，改「事件驱动为主 + 超时保险丝兜底」）。
 * <p>
 * 扫描判据（每轮，默认 60s）：
 * <ol>
 *   <li>取每个 topic 的末条消息（MAX id），只扫近 7 天活跃 topic（防全表扫）；topic 以
 *       {@code [fuse]} 前缀开头（历史告警）不判定——告警消息不参与超时判定，
 *       防「对自己告警」自触发；</li>
 *   <li>末条为系统消息（from_agent_id=0）→ 等待段结束，删 watch 行销案；</li>
 *   <li>自投线程（首条 from=to，交付回投 deliver-* 等）→ 无回音期待，不判定，销案；</li>
 *   <li>末条与线程首条（MIN id）方向相反（to→from 回音）→ 等待段结束，销案；
 *       同 topic 出现任何新消息（不论方向）都成为新末条，计时自动重置——打回重做自动获得新保险丝；</li>
 *   <li>消息级 0 档（expect_reply_minutes=0，bus-fuse-zero-optout）：显式「无需回音」，
 *       不参与熔断——跳过判定（不告警/不销案/不新增等待段）；缺省仍走全局默认；</li>
 *   <li>末条与首条同向（from→to，等待回音态）且 create_time + 生效时限已过 → 告警：
 *       生效时限 = 末条消息 expect_reply_minutes，空则走全局默认（env BUS_FUSE_DEFAULT_MINUTES，缺省 30）；
 *       全局默认不允许配 0（0 会整体关掉熔断，越界回退 30）；</li>
 *   <li>告警去重：t_bus_topic_watch.alerted_message_id 记已告警等待段的末条消息 id，
 *       同一等待段只告警一次；新消息成为新等待段后可再告警。</li>
 * </ol>
 * 告警投递（硬需求）：生成 msg_type=alert 系统消息（from=0）进总线，toAgentId=原 topic 发起人，
 * topic 加 {@code [fuse]} 前缀，session_key 固定取 env {@code BUS_ALERT_SESSION}（用户拍板：
 * 超时告警必须落管理员主会话窗口，不新开会话；未配置则仅落表并 warn 一次，不投递）。
 * 告警消息自身 status=0 进正常投递链（dispatcher 补偿/重投零改动），频控不适用（系统行为）。
 * <p>
 * 边界（任务书禁改清单）：不动 autoChatId、频控（429）、降级纪律、dispatcher 投递链——
 * 本组件只读消息表 + 维护 watch 表 + 追加告警消息，投递复用既有 {@code xaddQuietly}。
 * <p>
 * 线程模型：照 AuditFlushJob/ActivityCleanupJob 范式（ThreadPoolTaskScheduler 手动调度，
 * 不依赖 @EnableScheduling），单线程 fixedDelay；扫描异常全吞（log.error），不杀调度。
 */
@Slf4j
@Service
public class BusFuseService {

    /** 告警 topic 前缀（t_agent_message.topic 列宽 100，超长时截原 topic 保留前缀） */
    public static final String ALERT_TOPIC_PREFIX = "[fuse]";
    /** 系统告警消息的生产者 id（0=系统；不参与等待判定，防自触发） */
    public static final long SYSTEM_FROM_ID = 0L;
    /** topic 列宽（与 DDL 一致） */
    private static final int TOPIC_MAX = 100;
    /** 全局默认时限兜底（BUS_FUSE_DEFAULT_MINUTES 非法时回退，与任务书缺省一致） */
    private static final int DEFAULT_MINUTES_FALLBACK = 30;
    /** 全局默认时限下界（=1）：全局默认不允许 0（0 会整体关掉熔断），仅消息级允许 0（bus-fuse-zero-optout） */
    private static final int GLOBAL_DEFAULT_MIN_FLOOR = 1;
    /** 活跃 topic 窗口（任务书①：只扫近 7 天活跃 topic，防全表扫与陈年误报） */
    private static final long ACTIVE_WINDOW_MS = 7L * 24L * 3600L * 1000L;

    private final AgentMessageMapper messageMapper;
    private final BusTopicWatchMapper watchMapper;
    private final BusMessageService messageService;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final StringRedisTemplate redisTemplate;

    /** 保险丝开关（bus.fuse.enabled，缺省 true） */
    private final boolean enabled;
    /** 扫描间隔 ms（bus.fuse.scan-interval-ms，缺省 60000=60s；≤0 不启动，测试可调小） */
    private final long scanIntervalMs;
    /** 全局默认回音时限分钟（bus.fuse.default.minutes ← env BUS_FUSE_DEFAULT_MINUTES，缺省 30） */
    private final int defaultMinutes;
    /** 告警落点会话（bus.alert.session ← env BUS_ALERT_SESSION；空=未配置，告警仅落表） */
    private final String alertSession;

    private final ThreadPoolTaskScheduler taskScheduler;

    public BusFuseService(AgentMessageMapper messageMapper, BusTopicWatchMapper watchMapper,
            BusMessageService messageService, ObjectProvider<StringRedisTemplate> redisProvider,
            Environment environment) {
        this.messageMapper = messageMapper;
        this.watchMapper = watchMapper;
        this.messageService = messageService;
        this.redisProvider = redisProvider;
        this.redisTemplate = redisProvider.getIfAvailable();
        this.enabled = Boolean.parseBoolean(environment.getProperty("bus.fuse.enabled", "true").trim());
        long interval = parseLong(environment.getProperty("bus.fuse.scan-interval-ms", "60000"), 60_000L);
        this.scanIntervalMs = interval;
        this.defaultMinutes = (int) parseLong(environment.getProperty("bus.fuse.default.minutes", "30"),
                DEFAULT_MINUTES_FALLBACK);
        String session = environment.getProperty("bus.alert.session", "");
        this.alertSession = session == null || session.isBlank() ? null : session.trim();
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(1);
        this.taskScheduler.setThreadNamePrefix("bus-fuse-");
        this.taskScheduler.initialize();
        if (defaultMinutes < GLOBAL_DEFAULT_MIN_FLOOR
                || defaultMinutes > BusMessageService.EXPECT_REPLY_MAX_MINUTES) {
            log.warn("[bus-fuse] BUS_FUSE_DEFAULT_MINUTES 越界（{}），回退 {} 分钟",
                    defaultMinutes, DEFAULT_MINUTES_FALLBACK);
        }
    }

    /** 应用就绪后启动定时扫描（enabled=false 或间隔 ≤0 不启动；dev 测试可关） */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("[bus-fuse] 保险丝未启用（bus.fuse.enabled=false），不启动扫描");
            return;
        }
        if (scanIntervalMs <= 0) {
            log.info("[bus-fuse] 扫描间隔 <=0（bus.fuse.scan-interval-ms={}），不启动扫描", scanIntervalMs);
            return;
        }
        taskScheduler.scheduleWithFixedDelay(this::scanSafely, scanIntervalMs);
        log.info("[bus-fuse] 超时保险丝已启动: interval={}ms defaultMinutes={} alertSession={}",
                scanIntervalMs, effectiveDefaultMinutes(), alertSession == null ? "(未配置)" : "(已配置)");
    }

    @PreDestroy
    public void stop() {
        taskScheduler.shutdown();
    }

    /** 定时入口：异常全吞（不杀调度） */
    void scanSafely() {
        try {
            scanOnce();
        } catch (Exception e) {
            log.error("[bus-fuse] 扫描轮异常（下轮继续）: {}", e.getMessage(), e);
        }
    }

    /**
     * 单轮扫描（public 供测试/手动触发）：
     * 遍历近 7 天活跃 topic 的末条消息，按类注释判据处理（告警 / 销案 / 跳过）。
     */
    public void scanOnce() {
        Date now = new Date();
        List<BusTopicRange> ranges = messageMapper.selectTopicRanges(
                new Date(now.getTime() - ACTIVE_WINDOW_MS));
        for (BusTopicRange range : ranges) {
            AgentMessage last = range.getLastId() == null ? null : messageMapper.selectById(range.getLastId());
            if (last == null || last.getTopic() == null || last.getTopic().isBlank()) {
                continue;
            }
            if (isFuseTopic(last.getTopic())) {
                // [fuse] 前缀 = 历史告警 topic，不参与超时判定（防自触发）
                continue;
            }
            if (isSystemMessage(last)) {
                clearWatch(last.getTopic());
                continue;
            }
            AgentMessage first = range.getFirstId() != null && range.getFirstId().equals(range.getLastId())
                    ? last
                    : messageMapper.selectById(range.getFirstId());
            if (first == null) {
                continue;
            }
            if (first.getFromAgentId() != null
                    && first.getFromAgentId().equals(first.getToAgentId())) {
                // 自投线程（from=to，交付回投 deliver-* 等）：无外部回音期待，不参与超时判定
                clearWatch(last.getTopic());
                continue;
            }
            if (!sameDirectionAsFirst(first, last)) {
                // 末条是回音（to→from 反向）：等待段结束，销案
                clearWatch(last.getTopic());
                continue;
            }
            if (last.getExpectReplyMinutes() != null && last.getExpectReplyMinutes() == 0) {
                // 消息级 0 档（bus-fuse-zero-optout）：显式「无需回音」不参与熔断——
                // 不告警、不销案、不新增等待段；严禁当作「0 分钟超时立即告警」
                continue;
            }
            int minutes = effectiveMinutes(last);
            long deadline = last.getCreateTime().getTime() + minutes * 60_000L;
            if (now.getTime() < deadline) {
                continue;
            }
            BusTopicWatch watch = selectWatch(last.getTopic());
            if (watch != null) {
                boolean queuedBefore = watch.getAlertCount() != null && watch.getAlertCount() > 0;
                boolean sameSegment = last.getId().equals(watch.getAlertedMessageId());
                if (sameSegment && (queuedBefore || alertSession == null)) {
                    // 同等待段已投递过告警（去重，任务书验收⑤）；
                    // 或发现时 BUS_ALERT_SESSION 未配置仅 warn 过（不重复刷 warn）
                    continue;
                }
                // 其余情形可再告警：新等待段（回音后的打回/自续命），
                // 或此前仅 warn 而现在 BUS_ALERT_SESSION 已配置（升级补投）
            }
            fireAlert(last, minutes, watch);
        }
    }

    // ==================== 判据 ====================

    static boolean isFuseTopic(String topic) {
        return topic != null && topic.startsWith(ALERT_TOPIC_PREFIX);
    }

    static boolean isSystemMessage(AgentMessage row) {
        return row.getFromAgentId() == null || row.getFromAgentId() == SYSTEM_FROM_ID;
    }

    /** 末条与首条同向（from→to）= 等待回音态；反向（to→from 回音）= false */
    static boolean sameDirectionAsFirst(AgentMessage first, AgentMessage last) {
        return first.getFromAgentId() != null && first.getFromAgentId().equals(last.getFromAgentId())
                && first.getToAgentId() != null && first.getToAgentId().equals(last.getToAgentId());
    }

    /** 生效时限：消息级优先，空则全局默认 */
    private int effectiveMinutes(AgentMessage last) {
        Integer own = last.getExpectReplyMinutes();
        return own != null ? own : effectiveDefaultMinutes();
    }

    private int effectiveDefaultMinutes() {
        int dm = defaultMinutes;
        if (dm < GLOBAL_DEFAULT_MIN_FLOOR || dm > BusMessageService.EXPECT_REPLY_MAX_MINUTES) {
            return DEFAULT_MINUTES_FALLBACK;
        }
        return dm;
    }

    // ==================== 告警与 watch 维护 ====================

    /**
     * 发超时告警（系统消息，from=0）+ 登记等待段。
     * BUS_ALERT_SESSION 未配置：仅落表 warn（不生成告警消息），watch 记锚点
     * + alertCount=0 防同等待段重复 warn；env 后续配置后同段可升级补投。
     */
    private void fireAlert(AgentMessage last, int minutes, BusTopicWatch watch) {
        Date now = new Date();
        boolean queued = false;
        Long alertRowId = null;
        if (alertSession == null) {
            log.warn("[bus-fuse] 等待回音超时（BUS_ALERT_SESSION 未配置，告警不投递）: topic={} messageId={} 等待{}分钟",
                    last.getTopic(), last.getMessageId(), minutes);
        } else {
            AgentMessage alert = new AgentMessage();
            alert.setMessageId(UUID.randomUUID().toString());
            alert.setFromAgentId(SYSTEM_FROM_ID);
            alert.setToAgentId(last.getFromAgentId());
            alert.setMsgType("alert");
            alert.setTopic(buildAlertTopic(last.getTopic()));
            alert.setTitle("等待回音超时 " + minutes + " 分钟: " + last.getTopic());
            alert.setPayload(buildAlertPayload(last, minutes));
            alert.setSessionKey(alertSession);
            alert.setStatus(BusMessageService.STATUS_PENDING);
            alert.setRetryCount(0);
            alert.setCreateTime(now);
            alert.setUpdateTime(now);
            messageMapper.insert(alert);
            alertRowId = alert.getId();
            messageService.xaddQuietly(alert.getToAgentId(), alert.getMessageId(), alert.getFromAgentId(),
                    alert.getMsgType(), alert.getTopic(), alert.getTitle(), alert.getPayload());
            queued = true;
            log.warn("[bus-fuse] 超时告警已入队: sourceTopic={} sourceMessageId={} to={} 等待{}分钟 alertMessageId={}",
                    last.getTopic(), last.getMessageId(), last.getFromAgentId(), minutes, alert.getMessageId());
        }
        upsertWatch(last, now, queued);
        if (queued && redisTemplate == null) {
            log.debug("[bus-fuse] Redis 不可用，告警 {} 由补偿扫表重灌", alertRowId);
        }
    }

    private void upsertWatch(AgentMessage last, Date now, boolean queued) {
        BusTopicWatch watch = selectWatch(last.getTopic());
        if (watch == null) {
            BusTopicWatch row = new BusTopicWatch();
            row.setTopic(last.getTopic());
            row.setAlertedMessageId(last.getId());
            row.setAlertCount(queued ? 1 : 0);
            row.setLastAlertAt(queued ? now : null);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            watchMapper.insert(row);
            return;
        }
        BusTopicWatch upd = new BusTopicWatch();
        upd.setId(watch.getId());
        upd.setAlertedMessageId(last.getId());
        if (queued) {
            upd.setAlertCount((watch.getAlertCount() == null ? 0 : watch.getAlertCount()) + 1);
            upd.setLastAlertAt(now);
        }
        upd.setUpdateTime(now);
        watchMapper.updateById(upd);
    }

    /** 等待段结束销案（回音/系统消息收尾） */
    private void clearWatch(String topic) {
        int deleted = watchMapper.delete(
                Wrappers.<BusTopicWatch>lambdaQuery().eq(BusTopicWatch::getTopic, topic));
        if (deleted > 0) {
            log.info("[bus-fuse] 等待段结束销案: topic={}", topic);
        }
    }

    private BusTopicWatch selectWatch(String topic) {
        return watchMapper.selectOne(
                Wrappers.<BusTopicWatch>lambdaQuery().eq(BusTopicWatch::getTopic, topic));
    }

    // ==================== 形态构造 ====================

    /** 告警 topic：[fuse] + 原 topic；超列宽时截原 topic 部分（保留前缀） */
    static String buildAlertTopic(String topic) {
        String prefixed = ALERT_TOPIC_PREFIX + topic;
        if (prefixed.length() <= TOPIC_MAX) {
            return prefixed;
        }
        return ALERT_TOPIC_PREFIX + topic.substring(0, TOPIC_MAX - ALERT_TOPIC_PREFIX.length());
    }

    private String buildAlertPayload(AgentMessage last, int minutes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fuse", true);
        payload.put("sourceTopic", last.getTopic());
        payload.put("sourceMessageId", last.getMessageId());
        payload.put("sourceRowId", last.getId());
        payload.put("fromAgentId", last.getFromAgentId());
        payload.put("toAgentId", last.getToAgentId());
        payload.put("expectReplyMinutes", minutes);
        return BusEnvelopeFormatter.serializePayload(payload);
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
