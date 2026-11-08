package com.youlxb2008.admin.modules.bus.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 消息总线频控（M1，方案 §3.2：分钟 + 日双限）
 * <p>
 * Redis key（统一 ac:agent-bus: 前缀）：
 * <ul>
 *   <li>分钟：{@code ac:agent-bus:rate:{fromId}:{yyyyMMddHHmm}}，TTL 60s，≤600 条/min</li>
 *   <li>日：{@code ac:agent-bus:rate:daily:{fromId}:{yyyyMMdd}}，TTL 48h（key 按日轮换，48h 过期防堆积），≤10000 条/天</li>
 * </ul>
 * 红线（方案 §3.2 TTL 总则）：两个 key 首次 INCR 后必须 EXPIRE，禁止裸 INCR。
 * <p>
 * Redis 不可用（dev 无 Redis / StringRedisTemplate 为 null / 操作抛异常）时放行降级，
 * 与 {@code RateLimitFilter} 同款策略（消息已落表，dispatcher 补偿兜底，不因频控组件故障拒绝写入）。
 */
@Slf4j
@Component
public class BusRateLimiter {

    /** 分钟窗口阈值（条/min/生产者） */
    public static final int MINUTE_LIMIT = 600;
    /** 日窗口阈值（条/天/生产者） */
    public static final int DAILY_LIMIT = 10000;

    private static final String KEY_PREFIX = "ac:agent-bus:rate:";
    private static final String DAILY_KEY_PREFIX = "ac:agent-bus:rate:daily:";
    private static final Duration MINUTE_TTL = Duration.ofSeconds(60);
    private static final Duration DAILY_TTL = Duration.ofHours(48);
    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter DAILY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    public BusRateLimiter(ObjectProvider<StringRedisTemplate> redisProvider) {
        // dev/测试环境可能无 Redis（bean 不存在），取不到时传 null，由本类放行降级
        this.redisTemplate = redisProvider.getIfAvailable();
    }

    /**
     * 频控判定（分钟超限或日超限 → false，调用方转 429）
     *
     * @param fromAgentId 生产者 agent id
     * @return true=放行；false=超限；Redis 不可用=true（降级放行）
     */
    public boolean tryAcquire(long fromAgentId) {
        if (redisTemplate == null) {
            log.warn("[agent-bus] 频控降级：Redis 不可用，放行（from={}）", fromAgentId);
            return true;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            // 1. 分钟窗
            String minuteKey = KEY_PREFIX + fromAgentId + ":" + MINUTE_FMT.format(now);
            Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
            if (minuteCount == null) {
                log.warn("[agent-bus] 频控降级：increment 返回 null（key={}），放行", minuteKey);
                return true;
            }
            if (minuteCount == 1L) {
                // 仅首次 INCR 设置 TTL（含窗口余量），禁止裸 INCR
                redisTemplate.expire(minuteKey, MINUTE_TTL);
            }
            if (minuteCount > MINUTE_LIMIT) {
                log.warn("[agent-bus] 分钟频控触发：from={} count={}（限 {}）", fromAgentId, minuteCount, MINUTE_LIMIT);
                return false;
            }
            // 2. 日窗
            String dailyKey = DAILY_KEY_PREFIX + fromAgentId + ":" + DAILY_FMT.format(now);
            Long dailyCount = redisTemplate.opsForValue().increment(dailyKey);
            if (dailyCount == null) {
                log.warn("[agent-bus] 频控降级：increment 返回 null（key={}），放行", dailyKey);
                return true;
            }
            if (dailyCount == 1L) {
                redisTemplate.expire(dailyKey, DAILY_TTL);
            }
            if (dailyCount > DAILY_LIMIT) {
                log.warn("[agent-bus] 日频控触发：from={} count={}（限 {}）", fromAgentId, dailyCount, DAILY_LIMIT);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[agent-bus] 频控检查失败，放行降级: {}", e.getMessage());
            return true;
        }
    }
}
