package com.youlxb2008.admin.modules.agent.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ApiKeyAuthFilter 单元测试
 * 覆盖：有效 key 放行并写入 AgentContext、无效 key 401、suspended agent 403、管理端路径跳过
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

	@Mock
	private ApiKeyService apiKeyService;

	@Mock
	private AgentService agentService;

	@Mock
	private AuditService auditService;

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

	private ApiKeyAuthFilter filter;

	@BeforeEach
	void setUp() throws Exception {
		filter = new ApiKeyAuthFilter(apiKeyService, agentService, auditService, objectMapper);
		// 仅 denied 分支会写响应体；用 lenient 避免未使用分支报 UnnecessaryStubbing
		org.mockito.Mockito.lenient().when(response.getWriter()).thenReturn(writer);
	}

	private KeyIdentity identity() {
		KeyIdentity identity = new KeyIdentity();
		identity.setAgentId(1L);
		identity.setAgentName("bravo");
		identity.setKeyId(100L);
		identity.setScopes(List.of("notes:write", "audit:read"));
		identity.setStatus(1);
		return identity;
	}

	private Agent activeAgent() {
		Agent agent = new Agent();
		agent.setId(1L);
		agent.setName("bravo");
		agent.setStatus(1);
		return agent;
	}

	@Test
	@DisplayName("有效 key：放行并写入 AgentContext")
	void testValidKeyPasses() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/agent/me");
		when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn("ac_bravo_abcdefgh");
		when(apiKeyService.verifyKey("ac_bravo_abcdefgh")).thenReturn(identity());
		when(agentService.getActiveById(1L)).thenReturn(activeAgent());

		// 在 filterChain 执行瞬间捕获 AgentContext（filter 的 finally 会在请求结束后清理）
		final KeyIdentity[] captured = new KeyIdentity[1];
		org.mockito.Mockito.doAnswer(inv -> {
			captured[0] = AgentContext.get();
			return null;
		}).when(filterChain).doFilter(request, response);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		assertNotNull(captured[0]);
		assertEquals(1L, captured[0].getAgentId());
		assertEquals("bravo", captured[0].getAgentName());
		assertEquals(100L, captured[0].getKeyId());
		// 请求结束后上下文已清理，防止线程泄漏
		assertNull(AgentContext.get());
	}

	@Test
	@DisplayName("无效 key：401 且不进入后续链")
	void testInvalidKeyReturns401() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/agent/me");
		when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn("bad_key");
		when(apiKeyService.verifyKey("bad_key")).thenReturn(null);

		filter.doFilter(request, response, filterChain);

		verify(response).setStatus(401);
		verify(filterChain, never()).doFilter(request, response);
		// clientIp 可能为 null（mock 未 stub getRemoteAddr），用 any() 匹配
		verify(auditService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
	}

	@Test
	@DisplayName("suspended agent：403 且不进入后续链")
	void testSuspendedAgentReturns403() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/agent/me");
		when(request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER)).thenReturn("ac_bravo_abcdefgh");
		when(apiKeyService.verifyKey("ac_bravo_abcdefgh")).thenReturn(identity());
		when(agentService.getActiveById(1L)).thenReturn(null);

		filter.doFilter(request, response, filterChain);

		verify(response).setStatus(403);
		verify(filterChain, never()).doFilter(request, response);
	}

	@Test
	@DisplayName("管理端路径 /api/agent/admin/* 跳过认证")
	void testAdminPathSkipped() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/agent/admin/agents");

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(apiKeyService, never()).verifyKey(any());
	}

	@Test
	@DisplayName("非 agent 域路径跳过认证")
	void testNonAgentPathSkipped() throws Exception {
		when(request.getRequestURI()).thenReturn("/api/portal/site");

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(request, response);
		verify(apiKeyService, never()).verifyKey(any());
	}
}
