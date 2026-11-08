package com.youlxb2008.admin.modules.agent.vo;

import java.util.List;

import lombok.Data;

/**
 * API Key 身份信息（verifyKey 返回结构，契约 data-model.md §3）
 * <p>
 * 同时作为 Redis 缓存 {@code ac:key:{keyHash}} 的 JSON 载荷：
 * {@code {keyId, agentId, agentName, scopes, status}}，TTL 30s。
 * <ul>
 *   <li>keyId：t_api_key.id，限流 principal（契约 data-model.md §3 限流 key 用 keyId）</li>
 *   <li>agentName：agent 名，审计 principal</li>
 * </ul>
 */
@Data
public class KeyIdentity {

	/** t_api_key.id（限流 principal） */
	private Long keyId;

	/** 关联 t_agent.id */
	private Long agentId;

	/** agent 名（审计 principal） */
	private String agentName;

	/** 权限列表，如 ["notes:write","audit:read"] */
	private List<String> scopes;

	/** key 状态：1=active 0=revoked */
	private Integer status;
}
