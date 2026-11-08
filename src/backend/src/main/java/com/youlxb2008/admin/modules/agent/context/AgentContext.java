package com.youlxb2008.admin.modules.agent.context;

import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

/**
 * agent 域请求上下文（ThreadLocal）
 * <p>
 * 由 {@code ApiKeyAuthFilter} 认证通过后写入，请求结束（filter finally）清理，
 * 与管理端 {@code UserContext} 职责对齐。
 */
public class AgentContext {

	private static final ThreadLocal<KeyIdentity> HOLDER = new ThreadLocal<>();

	private AgentContext() {
	}

	public static void set(KeyIdentity identity) {
		HOLDER.set(identity);
	}

	public static KeyIdentity get() {
		return HOLDER.get();
	}

	/** 当前 agent id；未认证返回 null */
	public static Long getAgentId() {
		KeyIdentity identity = HOLDER.get();
		return identity != null ? identity.getAgentId() : null;
	}

	/** 当前 agent 名；未认证返回 null */
	public static String getAgentName() {
		KeyIdentity identity = HOLDER.get();
		return identity != null ? identity.getAgentName() : null;
	}

	/** 请求结束后清理，防止内存泄漏 */
	public static void clear() {
		HOLDER.remove();
	}
}
