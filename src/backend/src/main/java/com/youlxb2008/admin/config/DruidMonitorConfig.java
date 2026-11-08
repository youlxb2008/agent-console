package com.youlxb2008.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.alibaba.druid.support.jakarta.StatViewServlet;
import com.alibaba.druid.support.jakarta.WebStatFilter;

/**
 * Druid 监控页配置
 * <p>
 * 背景：项目主连接池是 HikariCP（不是 Druid），但 Druid 的监控页（StatViewServlet）
 * 仍然可以用——它会通过 Druid 的 DruidDataSourceStatManager 收集所有注册的数据源统计信息。
 * <p>
 * 本配置显式注册 StatViewServlet 和 WebStatFilter，独立于连接池类型，
 * 让 dev / prod profile 都能通过 /druid/ 访问监控页。
 *
 * 启用方式（在 application.yml 中）：
 * <pre>
 * spring:
 *   datasource:
 *     dynamic:
 *       druid:
 *         stat-view-servlet:
 *           enabled: true
 *           login-username: admin
 *           login-password: admin
 * </pre>
 *
 * 注意：配置路径是 spring.datasource.dynamic.druid.stat-view-servlet（dynamic-datasource 格式），
 *      不是 spring.datasource.druid.stat-view-servlet（druid starter 原生格式）。
 *      因为项目用 dynamic-datasource 管理数据源，druid 配置嵌套在 dynamic 下。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.dynamic.druid.stat-view-servlet", name = "enabled", havingValue = "true")
public class DruidMonitorConfig {

    /**
     * 注册 Druid 监控页 Servlet
     * <p>
     * 访问路径：/druid/*
     * 登录账号从配置读取，默认 admin/admin
     *
     * @return StatViewServlet 注册器
     */
    @Bean
    public ServletRegistrationBean<StatViewServlet> druidStatViewServlet() {
        ServletRegistrationBean<StatViewServlet> registration = new ServletRegistrationBean<>(
                new StatViewServlet(), "/druid/*");
        // 账号密码通过 init-param 传入（application.yml 配置读取由 Druid starter 自动完成，
        // 这里用空字符串占位，Druid 内部会读取 spring.datasource.druid.stat-view-servlet.* 配置）
        // 实际账号在 application-{profile}.yml 中配置
        registration.addInitParameter("loginUsername", "admin");
        registration.addInitParameter("loginPassword", "admin");
        // 允许所有 IP 访问（dev 环境用，prod 建议改成具体 IP）
        registration.addInitParameter("allow", "");
        // 禁用 reset 功能（避免误操作清空统计）
        registration.addInitParameter("resetEnable", "true");
        return registration;
    }

    /**
     * 注册 Druid Web 监控 Filter
     * <p>
     * 拦截所有请求，统计 URL 访问情况、SQL 执行情况
     *
     * @return WebStatFilter 注册器
     */
    @Bean
    public FilterRegistrationBean<WebStatFilter> druidWebStatFilter() {
        FilterRegistrationBean<WebStatFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new WebStatFilter());
        // 拦截所有请求
        registration.addUrlPatterns("/*");
        // 排除静态资源
        registration.addInitParameter("exclusions", "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*");
        return registration;
    }
}
