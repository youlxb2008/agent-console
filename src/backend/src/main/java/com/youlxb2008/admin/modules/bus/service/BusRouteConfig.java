package com.youlxb2008.admin.modules.bus.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 消息总线路由配置（M1，方案 §3.3）
 * <p>
 * 目标路由经 env 注入（禁入库、禁进 git）：
 * <pre>
 * BUS_TARGET_1=1|ws://your-host:8765|&lt;bootstrap_secret&gt;|models=*
 * # M2 追加：
 * BUS_TARGET_2=2|ws://your-agent-gateway:8766|&lt;secret&gt;|models=your-model-a,your-model-b
 * </pre>
 * 通过 Spring Environment 读取：{@code environment.getProperty("bus.target.1")} 会经
 * SystemEnvironmentPropertySource 的 relaxed binding 命中环境变量 {@code BUS_TARGET_1}；
 * 测试可用 properties 直接注入 {@code bus.target.1=...}。
 * <p>
 * 红线：bootstrap_secret 仅保存在内存字段，禁日志打印（toString 排除 secret）。
 */
@Slf4j
@Component
public class BusRouteConfig {

    /** 单实例最多解析的路由条数（防环境变量遍历失控） */
    private static final int MAX_TARGETS = 32;

    /** models 白名单通配值（不限制模型） */
    public static final String MODELS_ANY = "*";

    private final Environment environment;

    /** toAgentId → 路由行（启动时解析一次；M1 静态配置，改 env 需重启） */
    private final Map<Long, Route> routes = new LinkedHashMap<>();

    public BusRouteConfig(Environment environment) {
        this.environment = environment;
        reload();
    }

    /**
     * 重新解析路由（启动时调用；dispatcher 与校验链共用同一份解析结果）
     */
    public final synchronized void reload() {
        Map<Long, Route> parsed = new LinkedHashMap<>();
        for (int i = 1; i <= MAX_TARGETS; i++) {
            String line = environment.getProperty("bus.target." + i);
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                Route route = parseLine(line);
                if (route != null) {
                    Route prev = parsed.put(route.toAgentId(), route);
                    if (prev != null) {
                        log.warn("[agent-bus] BUS_TARGET_{} 与更早条目目标 agent 重复(to={})，后到覆盖先到", i, route.toAgentId());
                    }
                }
            } catch (Exception e) {
                // 配置行非法：启动不阻断（fail-safe），该目标不可用并在日志可见
                log.error("[agent-bus] BUS_TARGET_{} 解析失败，忽略该目标: {}", i, e.getMessage());
            }
        }
        routes.clear();
        routes.putAll(parsed);
        log.info("[agent-bus] 路由解析完成：{} 个目标 -> {}", routes.size(), routes.keySet());
    }

    /** 目标是否已接入总线（存在路由行） */
    public boolean hasRoute(long toAgentId) {
        return routes.containsKey(toAgentId);
    }

    /** 取目标路由（无则 empty） */
    public synchronized Optional<Route> routeOf(long toAgentId) {
        return Optional.ofNullable(routes.get(toAgentId));
    }

    /** 全部路由（dispatcher 为每个目标启动消费循环） */
    public synchronized List<Route> allRoutes() {
        return Collections.unmodifiableList(new ArrayList<>(routes.values()));
    }

    /**
     * model 白名单校验（方案 §4.1 校验链第 9 步）：
     * models=* 不限；否则 model（trim，忽略大小写）须在白名单内。
     */
    public boolean modelAllowed(long toAgentId, String model) {
        Optional<Route> route = routeOf(toAgentId);
        if (route.isEmpty()) {
            return false;
        }
        Set<String> models = route.get().models();
        if (models.contains(MODELS_ANY)) {
            return true;
        }
        if (model == null || model.isBlank()) {
            // 不指定模型 = 收件人默认 preset，不受白名单约束（白名单只约束显式指定）
            return true;
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        return models.stream().anyMatch(m -> m.trim().toLowerCase(Locale.ROOT).equals(normalized));
    }

    /** stream key：ac:agent-bus:stream:{toAgentId}（按目标分片，方案 §3.2） */
    public static String streamKey(long toAgentId) {
        return "ac:agent-bus:stream:" + toAgentId;
    }

    /**
     * 解析单行路由配置：{@code toId|wsUrl|secret|models=a,b,c}
     * models 段可省略（等价 models=*）；非法行抛 IllegalArgumentException。
     */
    public static Route parseLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("route line is null");
        }
        String[] parts = line.split("\\|");
        if (parts.length < 3) {
            throw new IllegalArgumentException("route line 需要 toId|wsUrl|secret|models=.. 四段（models 可省），实际 " + parts.length + " 段");
        }
        long toId;
        try {
            toId = Long.parseLong(parts[0].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("route toId 非数字: " + parts[0]);
        }
        String wsUrl = parts[1].trim();
        if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
            throw new IllegalArgumentException("route wsUrl 须以 ws:// 或 wss:// 开头");
        }
        String secret = parts[2].trim();
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("route bootstrap secret 不能为空");
        }
        Set<String> models;
        if (parts.length >= 4) {
            String m = parts[3].trim();
            if (m.toLowerCase(Locale.ROOT).startsWith("models=")) {
                m = m.substring("models=".length());
            }
            final String raw = m;
            models = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (models.isEmpty()) {
                models = Set.of(MODELS_ANY);
            }
        } else {
            models = Set.of(MODELS_ANY);
        }
        return new Route(toId, wsUrl, secret, Set.copyOf(models));
    }

    /**
     * 目标路由（secret 不进 toString/日志，红线 §9.3）
     */
    public record Route(long toAgentId, String wsUrl, String secret, Set<String> models) {

        @Override
        public String toString() {
            return "Route{toAgentId=" + toAgentId + ", wsUrl=" + wsUrl + ", models=" + models + ", secret=***}";
        }

        /** bootstrap HTTP 基址：ws://h:p → http://h:p（wss → https） */
        public String httpBaseUrl() {
            if (wsUrl.startsWith("wss://")) {
                return "https://" + wsUrl.substring("wss://".length());
            }
            return "http://" + wsUrl.substring("ws://".length());
        }
    }
}
