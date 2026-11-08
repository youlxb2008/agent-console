package com.youlxb2008.admin.modules.agent.filter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.utils.ResultVO;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 限流过滤器（契约 data-model.md §3）
 * <p>
 * Redis key {@code ac:ratelimit:{principal}:{yyyyMMddHHmm}}（String 计数，TTL 60s）：
 * <ul>
 *   <li>agent 域：principal=keyId（契约 §3），阈值 600/min（2026-10-07 用户拍板放宽，总线重日可打满旧值）</li>
 *   <li>门户域：principal=IP，阈值 600/min（2026-09-30 用户拍板放宽，密集点验易撞）</li>
 * </ul>
 * 超限返回 429；Redis 不可用（dev 无 Redis）时放行降级。
 * <p>
 * 注册方式见 {@code AgentFilterConfig}（FilterRegistrationBean，order=2，在 ApiKeyAuthFilter 之后，
 * 保证 agent 域请求已认证、AgentContext 中可拿到 keyId）。
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

	private static final String RATE_LIMIT_PREFIX = "ac:ratelimit:";
	private static final Duration WINDOW = Duration.ofSeconds(60);
	private static final int AGENT_LIMIT = 600;
	private static final int PORTAL_LIMIT = 600;
	private static final String ADMIN_PREFIX = "/api/agent/admin/";
	private static final String AGENT_PREFIX = "/api/agent/";
	private static final String PORTAL_PREFIX = "/api/portal/";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public RateLimitFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		// 管理端走 JWT，不限流
		if (path.startsWith(ADMIN_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}
		boolean isAgent = path.startsWith(AGENT_PREFIX);
		boolean isPortal = path.startsWith(PORTAL_PREFIX);
		if (!isAgent && !isPortal) {
			filterChain.doFilter(request, response);
			return;
		}

		// Redis 不可用（dev 无 Redis）时放行降级
		if (redisTemplate == null) {
			filterChain.doFilter(request, response);
			return;
		}

		String principal = isAgent ? agentPrincipal() : null;
		if (principal == null || principal.isBlank()) {
			principal = clientIp(request);
		}
		int limit = isAgent ? AGENT_LIMIT : PORTAL_LIMIT;

		String minute = DateTimeFormatter.ofPattern("yyyyMMddHHmm").format(LocalDateTime.now());
		String key = RATE_LIMIT_PREFIX + principal + ":" + minute;
		try {
			Long count = redisTemplate.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redisTemplate.expire(key, WINDOW);
			}
			if (count != null && count > limit) {
				writeTooMany(response);
				return;
			}
		} catch (Exception e) {
			log.warn("限流检查失败，放行: {}", e.getMessage());
		}
		filterChain.doFilter(request, response);
	}

	/** agent 域 principal=keyId（契约 data-model.md §3） */
	private String agentPrincipal() {
		var identity = AgentContext.get();
		if (identity != null && identity.getKeyId() != null) {
			return String.valueOf(identity.getKeyId());
		}
		return null;
	}

	private void writeTooMany(HttpServletResponse response) throws IOException {
		response.setStatus(429);
		response.setContentType("application/json;charset=UTF-8");
		ResultVO vo = new ResultVO();
		vo.setCode("429");
		vo.setMsg("请求过于频繁，请稍后再试");
		vo.setSuccess(false);
		response.getWriter().write(objectMapper.writeValueAsString(vo));
	}

	private String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
