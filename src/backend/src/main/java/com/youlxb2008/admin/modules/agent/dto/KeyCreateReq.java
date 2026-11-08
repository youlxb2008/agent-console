package com.youlxb2008.admin.modules.agent.dto;

import java.util.Date;
import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理端：生成 API Key 请求（契约 api.md §2，POST /keys）
 * <p>
 * 明文 key 只在响应返回一次，不落库、不写日志。
 */
@Data
public class KeyCreateReq {

	/** 关联 t_agent.id */
	@NotNull(message = "agentId 不能为空")
	private Long agentId;

	/** 权限列表（如 notes:write / audit:read） */
	@NotEmpty(message = "scopes 不能为空")
	private List<String> scopes;

	/** 过期时间，null=永久 */
	private Date expiresAt;
}
