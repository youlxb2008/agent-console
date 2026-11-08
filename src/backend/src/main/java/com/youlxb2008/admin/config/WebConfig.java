package com.youlxb2008.admin.config;

import java.io.IOException;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Web 配置类
 * <p>
 * 历史曾通过 Spring Security 的 HttpSecurity.headers() 配置，
 * 删除 Spring Security 后，H2 Console 所需的 frameOptions 配置迁移到此处。
 */
@Configuration
public class WebConfig {

    /**
     * 设置 X-Frame-Options: SAMEORIGIN
     * <p>
     * H2 Console 在 iframe 中运行需要此响应头，否则会被浏览器拒绝。
     * 全局设置为 SAMEORIGIN 兼顾 H2 Console 与安全性。
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> frameOptionsFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                response.setHeader("X-Frame-Options", "SAMEORIGIN");
                filterChain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setName("frameOptionsFilter");
        return registration;
    }
}
