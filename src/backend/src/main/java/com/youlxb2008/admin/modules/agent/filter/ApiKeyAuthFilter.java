package com.youlxb2008.admin.modules.agent.filter;

import java.io.IOException;

import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;
import com.youlxb2008.admin.utils.ResultVO;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域 API Key 认证过滤器（契约 api.md §0）
 * <p>
 * 拦截 {@code /api/agent/*}（排除 {@code /api/agent/admin/*} 管理端，走 JWT）：
 * <ol>
 *   <li>Header {@code X-API-Key} → {@link ApiKeyService#verifyKey}（SHA-256 查缓存/DB）</li>
 *   <li>key 无效 → 401；agent 非 active（suspended）→ 403</li>
 *   <li>认证通过：{@link KeyIdentity} 写入 {@link AgentContext}（ThreadLocal）</li>
 * </ol>
 * scope 校验由 {@code @RequireScope} + {@code ScopeAspect} 在 Controller 方法上完成（不足 → 403）。
 * 每次调用（含 denied）写审计（result=success/denied）。
 * <p>
 * 注册方式见 {@code AgentFilterConfig}（FilterRegistrationBean，order=1，先于限流）。
 */
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	/** API Key 请求头 */
	public static final String API_KEY_HEADER = "X-API-Key";

	private static final String AGENT_PREFIX = "/api/agent/";
	private static final String ADMIN_PREFIX = "/api/agent/admin/";

	private final ApiKeyService apiKeyService;
	private final AgentService agentService;
	private final AuditService auditService;
	private final ObjectMapper objectMapper;

	public ApiKeyAuthFilter(ApiKeyService apiKeyService, AgentService agentService, AuditService auditService,
			ObjectMapper objectMapper) {
		this.apiKeyService = apiKeyService;
		this.agentService = agentService;
		this.auditService = auditService;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String path = request.getRequestURI();
		// 只拦截 /api/agent/*，排除 /api/agent/admin/*（管理端走 JWT）
		if (!path.startsWith(AGENT_PREFIX) || path.startsWith(ADMIN_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		String plainKey = request.getHeader(API_KEY_HEADER);
		KeyIdentity identity = apiKeyService.verifyKey(plainKey);
		if (identity == null) {
			writeDenied(response, HttpServletResponse.SC_UNAUTHORIZED, "无效的 API Key");
			audit(request, "unknown", actionForPath(path), "denied");
			return;
		}

		// agent 必须 active（suspended → 403）
		Agent agent = agentService.getActiveById(identity.getAgentId());
		if (agent == null) {
			String principal = identity.getAgentName() != null ? identity.getAgentName()
					: String.valueOf(identity.getAgentId());
			writeDenied(response, HttpServletResponse.SC_FORBIDDEN, "agent 已被停用");
			audit(request, principal, actionForPath(path), "denied");
			return;
		}

		try {
			AgentContext.set(identity);
			filterChain.doFilter(request, response);
		} finally {
			AgentContext.clear();
		}
	}

	/** 路径 → 审计 action（denied 也记） */
	private String actionForPath(String path) {
		if (path.equals("/api/agent/notes")) {
			return "note.write";
		}
		if (path.equals("/api/agent/audit")) {
			return "audit.read";
		}
		if (path.equals("/api/agent/me")) {
			return "auth.me";
		}
		// v4.1：知识库接口（filter 层 401/403 拒绝时也按真实 action 记审计）
		if (path.equals("/api/agent/kb/search")) {
			return "kb.search";
		}
		if (path.equals("/api/agent/kb/ask")) {
			return "kb.ask";
		}
		// v4.2：文档端点（agent 拉取 SKILL 概览/API 参考，denied 时也按真实 action 记审计）
		if (path.equals("/api/agent/skill-doc")) {
			return "doc.skill";
		}
		if (path.equals("/api/agent/api-docs")) {
			return "doc.api";
		}
		return "api.access";
	}

	private void writeDenied(HttpServletResponse response, int status, String msg) throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		ResultVO vo = new ResultVO();
		vo.setCode(String.valueOf(status));
		vo.setMsg(msg);
		vo.setSuccess(false);
		response.getWriter().write(objectMapper.writeValueAsString(vo));
	}

	private void audit(HttpServletRequest request, String principal, String action, String result) {
		try {
			auditService.append(principal, action, request.getRequestURI(), requestSummary(request), result,
					clientIp(request));
		} catch (Exception e) {
			log.warn("审计写入失败: {}", e.getMessage());
		}
	}

	private String requestSummary(HttpServletRequest request) {
		return "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}";
	}

	private String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].trim();
		}
		return request.getRemoteAddr();
	}
}
