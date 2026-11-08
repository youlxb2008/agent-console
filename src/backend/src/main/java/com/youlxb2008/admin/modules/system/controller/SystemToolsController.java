package com.youlxb2008.admin.modules.system.controller;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;

/**
 * 系统工具箱（仅超管可见）
 * 提供对外暴露的服务地址列表 + 健康检查 / 应用信息查询
 */
@Tag(name = "系统工具箱", description = "对外暴露的服务地址、健康检查、应用信息")
@RestController
@RequestMapping("/system/tools")
public class SystemToolsController {

    private final Environment env;

    /**
     * 构造注入 Environment，用于读取应用配置
     */
    public SystemToolsController(Environment env) {
        this.env = env;
    }

    /**
     * 服务端点信息 DTO
     * name: 端点名称（如 Swagger UI、H2 控制台）
     * url: 端点完整访问地址
     * category: 分类 — link=外链卡片, status=状态页内嵌
     * description: 端点功能描述
     */
    @Data
    public static class EndpointInfo {
        /** 端点名称 */
        private String name;
        /** 端点访问地址 */
        private String url;
        /** 分类：link=外链, status=状态页 */
        private String category;
        /** 功能描述 */
        private String description;
    }

    /**
     * 获取所有对外暴露的服务端点
     * 只有拥有 system:tools 权限的用户才能访问
     *
     * @return 端点列表，包含外链类（Swagger/H2/Druid）和状态页类（健康检查/应用信息）
     */
    @Operation(summary = "获取服务端点列表")
    @GetMapping("/endpoints")
    @RequiresPermissions("system:tools")
    public ResultVO getEndpoints() {
        // 读取服务端口，默认 8000
        String serverPort = env.getProperty("server.port", "8000");
        // 读取上下文路径，确保以 / 开头和结尾
        String contextPath = env.getProperty("server.servlet.context-path", "/");
        if (!contextPath.startsWith("/")) {
            contextPath = "/" + contextPath;
        }
        if (!contextPath.endsWith("/")) {
            contextPath = contextPath + "/";
        }

        // 获取本机 IP 地址，获取失败时回退到 localhost
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            host = "localhost";
        }
        // 拼接基础 URL：http://host:port/contextPath
        String baseUrl = "http://" + host + ":" + serverPort + contextPath;

        List<EndpointInfo> endpoints = new ArrayList<>();

        /* ====== 外链类端点 ====== */

        // Swagger UI — API 文档与在线调试
        EndpointInfo swagger = new EndpointInfo();
        swagger.setName("Swagger UI");
        swagger.setUrl(baseUrl + "swagger/");
        swagger.setCategory("link");
        swagger.setDescription("API 文档与在线调试");
        endpoints.add(swagger);

        // H2 Console — 仅在 spring.h2.console.enabled=true 时展示
        Boolean h2Enabled = env.getProperty("spring.h2.console.enabled", Boolean.class, false);
        if (h2Enabled) {
            EndpointInfo h2 = new EndpointInfo();
            h2.setName("H2 控制台");
            // 读取 H2 JDBC URL，优先从动态数据源配置读取
            String h2JdbcUrl = env.getProperty(
                    "spring.datasource.dynamic.datasource.primary.url",
                    env.getProperty("spring.datasource.url", ""));
            // 预填连接参数，方便直接登录
            String h2FullUrl = baseUrl + "h2-console/?driver=org.h2.Driver"
                    + "&url=" + URLEncoder.encode(h2JdbcUrl, StandardCharsets.UTF_8)
                    + "&user=sa&password=";
            h2.setUrl(h2FullUrl);
            h2.setCategory("link");
            h2.setDescription("H2 数据库控制台（已预填连接信息）");
            endpoints.add(h2);
        }

        // Druid 监控 — 仅在 stat-view-servlet.enabled=true 时展示
        Boolean druidEnabled = env.getProperty(
                "spring.datasource.dynamic.druid.stat-view-servlet.enabled", Boolean.class, false);
        if (druidEnabled) {
            EndpointInfo druid = new EndpointInfo();
            druid.setName("Druid 监控");
            druid.setUrl(baseUrl + "druid/index.html");
            druid.setCategory("link");
            druid.setDescription("SQL 监控、连接池状态、慢查询分析");
            endpoints.add(druid);
        }

        /* ====== 状态页类端点 ====== */

        // 读取 Actuator 暴露的端点列表
        String exposed = env.getProperty("management.endpoints.web.exposure.include", "");

        // 健康检查端点 — 仅在 actuator 暴露了 health 时展示
        if (exposed.contains("health")) {
            EndpointInfo health = new EndpointInfo();
            health.setName("健康检查");
            health.setUrl(baseUrl + "actuator/health");
            health.setCategory("status");
            health.setDescription("应用健康状态（数据库、磁盘、Redis 等）");
            endpoints.add(health);
        }

        // 应用信息端点 — 仅在 actuator 暴露了 info 时展示
        if (exposed.contains("info")) {
            EndpointInfo info = new EndpointInfo();
            info.setName("应用信息");
            info.setUrl(baseUrl + "actuator/info");
            info.setCategory("status");
            info.setDescription("应用名称、版本号、构建信息");
            endpoints.add(info);
        }

        return ResultVO.getSuccessVO("查询成功").setData(endpoints);
    }
}
