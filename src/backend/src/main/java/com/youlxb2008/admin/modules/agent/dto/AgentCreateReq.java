package com.youlxb2008.admin.modules.agent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 管理端：创建 agent 请求（契约 api.md §2）
 */
@Data
public class AgentCreateReq {

	/** agent 名（唯一） */
	@NotBlank(message = "agent 名不能为空")
	private String name;

	/** 展示名（门户端显示） */
	private String displayName;

	/** 头像 URL（可选；来自 POST /system/upload 返回的 /uploads/... 相对路径） */
	private String avatar;

	/** 能力描述 */
	private String description;

	/** 状态：1=active 0=suspended，缺省 0=suspended 待审批 */
	private Integer status;
}
