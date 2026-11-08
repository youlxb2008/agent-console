package com.youlxb2008.admin.modules.agent.filter;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RateLimitFilter 单元测试
 * 覆盖：未超限放行、超限 429、agent 域 principal=keyId、Redis 不可用放行降级
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOps;

	@Spy
	private ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private PrintWriter writer;

	@Mock
	private FilterChain filterChain;

	private RateLimitFilter filter;

	@BeforeEach
	void setUp() throws Exception {
		filter = new RateLimitFilter(redisTemplate, objectMapper);
		// 部分用例（管理端跳过 / Redis 降级）不触达 Redis 与响应体，用 lenient 避免 UnnecessaryStubbing
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
		lenient().when(response.getWriter()).thenReturn(writer);
	}

	@Test
	@DisplayName("未超限：放行并设置 60s TTL")
	void testUnderLimitPasses() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/portal/site");
		when(request.getRemoteAddr()).thenReturn("1.2.3.4");
		when(valueOps.increment(anyString())).thenReturn(1L);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(redisTemplate).expire(anyString(), any());
	}

	@Test
	@DisplayName("门户超限（>600）：429 且不进入后续链")
	void testPortalOverLimitReturns429() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/portal/site");
		when(request.getRemoteAddr()).thenReturn("1.2.3.4");
		when(valueOps.increment(anyString())).thenReturn(601L);

		filter.doFilter(request, response, filterChain);

		verify(response).setStatus(429);
		verify(filterChain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("agent 域超限（>600）：429")
	void testAgentOverLimitReturns429() throws Exception {
		KeyIdentity identity = new KeyIdentity();
		identity.setKeyId(100L);
		AgentContext.set(identity);
		when(request.getRequestURI()).thenReturn("/api/agent/notes");
		when(valueOps.increment(anyString())).thenReturn(601L);

		filter.doFilter(request, response, filterChain);

		verify(response).setStatus(429);
		verify(filterChain, never()).doFilter(request, response);
		AgentContext.clear();
	}

	@Test
	@DisplayName("agent 域 principal=keyId（契约 data-model.md §3）")
	void testAgentPrincipalUsesKeyId() throws Exception {
		KeyIdentity identity = new KeyIdentity();
		identity.setKeyId(100L);
		AgentContext.set(identity);
		when(request.getRequestURI()).thenReturn("/api/agent/notes");
		when(valueOps.increment(anyString())).thenReturn(1L);

		filter.doFilter(request, response, filterChain);

		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(valueOps).increment(captor.capture());
		assertTrue(captor.getValue().startsWith("ac:ratelimit:100:"), "agent 域限流 key 应含 keyId");
		AgentContext.clear();
	}

	@Test
	@DisplayName("管理端路径 /api/agent/admin/* 不限流")
	void testAdminPathSkipped() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/agent/admin/agents");

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(valueOps, never()).increment(anyString());
	}

	@Test
	@DisplayName("Redis 不可用（dev 无 Redis）：放行降级")
	void testRedisNullPasses() throws Exception {
		RateLimitFilter noRedis = new RateLimitFilter(null, objectMapper);
		when(request.getRequestURI()).thenReturn("/api/portal/site");

		noRedis.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
	}
}
