package com.youlxb2008.admin.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.agent.filter.ApiKeyAuthFilter;
import com.youlxb2008.admin.modules.agent.filter.RateLimitFilter;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.agent.service.AuditService;

/**
 * agent 域过滤器注册配置（见 WebConfig.frameOptionsFilter）
 * <p>
 * 用 FilterRegistrationBean 显式注册并控制执行顺序：
 * <ol>
 *   <li>ApiKeyAuthFilter（order=1）：/api/agent/* 认证（排除 /api/agent/admin/*）</li>
 *   <li>RateLimitFilter（order=2）：/api/agent/* + /api/portal/* 限流</li>
 * </ol>
 * 与 WebConfig.frameOptionsFilter 一致：filter 实例在 @Bean 方法内用 {@code new} 创建，
 * 依赖通过 @Bean 方法参数注入，不把 filter 声明为 @Component，避免被 Spring Boot
 * 按 {@code /*} 自动注册导致重复执行。
 */
@Configuration
public class AgentFilterConfig {

	@Bean
	public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(ApiKeyService apiKeyService,
			AgentService agentService, AuditService auditService, ObjectMapper objectMapper) {
		FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
				new ApiKeyAuthFilter(apiKeyService, agentService, auditService, objectMapper));
		registration.addUrlPatterns("/api/agent/*");
		registration.setName("apiKeyAuthFilter");
		registration.setOrder(1);
		return registration;
	}

	@Bean
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
			ObjectProvider<StringRedisTemplate> redisProvider, ObjectMapper objectMapper) {
		// dev/测试环境可能无 Redis（StringRedisTemplate bean 不存在），取不到时传 null，由过滤器放行降级
		FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
				new RateLimitFilter(redisProvider.getIfAvailable(), objectMapper));
		registration.addUrlPatterns("/api/agent/*", "/api/portal/*");
		registration.setName("rateLimitFilter");
		registration.setOrder(2);
		return registration;
	}
}
