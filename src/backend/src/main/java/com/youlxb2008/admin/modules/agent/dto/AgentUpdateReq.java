package com.youlxb2008.admin.modules.agent.dto;

import lombok.Data;

/**
 * 管理端：更新 agent 请求（契约 api.md §2，PUT /agents/{id}）
 * <p>
 * 只允许改 display_name/avatar/description/status（name 为唯一键不可改）。
 * 各字段均可选，非 null 才更新；avatar 传空串表示清除头像（回退色块兜底）。
 */
@Data
public class AgentUpdateReq {

	/** 展示名 */
	private String displayName;

	/** 头像 URL（空串=清除头像；null=不修改） */
	private String avatar;

	/** 能力描述 */
	private String description;

	/** 状态：1=active 0=suspended（suspend 即刻生效，缓存 30s 内过期） */
	private Integer status;
}
