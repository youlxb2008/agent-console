package com.youlxb2008.admin.modules.agent.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.UnauthorizedException;
import com.youlxb2008.admin.modules.agent.annotation.RequireScope;
import com.youlxb2008.admin.modules.agent.context.AgentContext;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * agent 域 scope 校验切面（契约 api.md §0）
 * <p>
 * 拦截 {@code @RequireScope} 注解：校验当前 API Key 的 scopes 是否满足所需 scope。
 * 不满足时写审计（result=denied）并抛 403；满足则放行（success 审计由 Controller 记录）。
 */
@Slf4j
@Aspect
@Component
public class ScopeAspect {

	@Autowired
	private AuditService auditService;

	@Around("@annotation(requireScope)")
	public Object checkScope(ProceedingJoinPoint joinPoint, RequireScope requireScope) throws Throwable {
		KeyIdentity identity = AgentContext.get();
		if (identity == null) {
			throw new UnauthorizedException("未认证或认证已失效");
		}

		boolean hasScope = identity.getScopes() != null && identity.getScopes().contains(requireScope.value());
		if (!hasScope) {
			HttpServletRequest request = getRequest();
			String uri = request != null ? request.getRequestURI() : "";
			String ip = request != null ? getClientIp(request) : "";
			auditService.append(identity.getAgentName(), requireScope.action(), uri,
					summary(request), "denied", ip);
			log.warn("agent scope 不足：agent={} scope={} uri={}", identity.getAgentName(), requireScope.value(), uri);
			throw new ForbiddenException("scope 不足：" + requireScope.value());
		}
		return joinPoint.proceed();
	}

	private HttpServletRequest getRequest() {
		var attrs = RequestContextHolder.getRequestAttributes();
		if (attrs instanceof ServletRequestAttributes) {
			return ((ServletRequestAttributes) attrs).getRequest();
		}
		return null;
	}

	private String getClientIp(HttpServletRequest request) {
		String ip = request.getHeader("X-Forwarded-For");
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getHeader("X-Real-IP");
		}
		if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
			ip = request.getRemoteAddr();
		}
		return ip;
	}

	private String summary(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		return "{\"method\":\"" + request.getMethod() + "\",\"path\":\"" + request.getRequestURI() + "\"}";
	}
}
