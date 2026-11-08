package com.youlxb2008.admin.modules.bus.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * api 通道目标配置（bus-route-channel）
 * <p>
 * 目标端点经 env 注入（禁入库、禁进 git），与 WS 路由（BUS_TARGET_*）平行：
 * <pre>
 * BUS_API_TARGET_1=2|http://your-host:8900|apikey-xxxx
 * # 格式：{agentId}|{baseUrl}|{apiKey}，n=1..8；baseUrl 为 nanobot OpenAI 兼容 API 基址
 * </pre>
 * 通过 Spring Environment 读取：{@code environment.getProperty("bus.api.target.1")} 经
 * SystemEnvironmentPropertySource relaxed binding 命中环境变量 {@code BUS_API_TARGET_1}；
 * 测试可用 properties 直接注入。启动解析一次，改 env 需重启（与 BUS_TARGET_* 口径一致）。
 * <p>
 * 红线：apiKey 仅保存在内存字段，禁日志打印（toString 排除 key）。
 */
@Slf4j
@Component
public class BusApiTargetConfig {

    /** 最多解析的目标条数（任务书 §5：n=1..8） */
    public static final int MAX_TARGETS = 8;

    private final Environment environment;

    /** toAgentId → api 目标（启动时解析一次） */
    private final Map<Long, ApiTarget> targets = new LinkedHashMap<>();

    public BusApiTargetConfig(Environment environment) {
        this.environment = environment;
        reload();
    }

    /**
     * 重新解析 api 目标（启动时调用；dispatcher 与降级判定共用同一份解析结果）
     */
    public final synchronized void reload() {
        Map<Long, ApiTarget> parsed = new LinkedHashMap<>();
        for (int i = 1; i <= MAX_TARGETS; i++) {
            String line = environment.getProperty("bus.api.target." + i);
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                ApiTarget target = parseLine(line);
                if (target != null) {
                    ApiTarget prev = parsed.put(target.agentId(), target);
                    if (prev != null) {
                        log.warn("[agent-bus] BUS_API_TARGET_{} 与更早条目目标 agent 重复(to={})，后到覆盖先到",
                                i, target.agentId());
                    }
                }
            } catch (Exception e) {
                // 配置行非法：启动不阻断（fail-safe），该目标 api 不可用（投递时降级 ws）并在日志可见
                log.error("[agent-bus] BUS_API_TARGET_{} 解析失败，忽略该目标: {}", i, e.getMessage());
            }
        }
        targets.clear();
        targets.putAll(parsed);
        log.info("[agent-bus] api 目标解析完成：{} 个 -> {}", targets.size(), targets.keySet());
    }

    /** 目标是否有 api 通道配置（无配置 = api 模式降级 ws） */
    public boolean hasTarget(long toAgentId) {
        return targets.containsKey(toAgentId);
    }

    /** 取目标 api 配置（无则 empty） */
    public synchronized Optional<ApiTarget> targetOf(long toAgentId) {
        return Optional.ofNullable(targets.get(toAgentId));
    }

    /** 全部 api 目标 */
    public synchronized List<ApiTarget> allTargets() {
        return Collections.unmodifiableList(new ArrayList<>(targets.values()));
    }

    /**
     * 解析单行 api 目标配置：{@code agentId|baseUrl|apiKey}
     * baseUrl 须以 http:// 或 https:// 开头；非法行抛 IllegalArgumentException。
     */
    public static ApiTarget parseLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("api target line is null");
        }
        String[] parts = line.split("\\|");
        if (parts.length != 3) {
            throw new IllegalArgumentException("api target line 需要 agentId|baseUrl|apiKey 三段，实际 " + parts.length + " 段");
        }
        long agentId;
        try {
            agentId = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("api target agentId 非数字: " + parts[0]);
        }
        String baseUrl = parts[1].trim();
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("api target baseUrl 须以 http:// 或 https:// 开头");
        }
        String apiKey = parts[2].trim();
        if (apiKey.isEmpty()) {
            throw new IllegalArgumentException("api target apiKey 不能为空");
        }
        return new ApiTarget(agentId, baseUrl, apiKey);
    }

    /**
     * api 投递目标（apiKey 不进 toString/日志，红线同 BusRouteConfig.Route）
     */
    public record ApiTarget(long agentId, String baseUrl, String apiKey) {

        @Override
        public String toString() {
            return "ApiTarget{agentId=" + agentId + ", baseUrl=" + baseUrl + ", apiKey=***}";
        }
    }
}
