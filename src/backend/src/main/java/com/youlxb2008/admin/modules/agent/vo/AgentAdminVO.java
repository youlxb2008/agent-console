package com.youlxb2008.admin.modules.agent.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 管理端：agent 列表项（契约 api.md §2，GET /agents）
 * <p>
 * 含 key 数（active）、最近使用时间（聚合自 t_api_key）。
 */
@Data
public class AgentAdminVO {

	private Long id;

	private String name;

	private String displayName;

	/** 头像 URL（/uploads/... 相对路径；null=无头像，前端色块兜底） */
	private String avatar;

	private String description;

	/** 1=active 0=suspended */
	private Integer status;

	/** active key 数 */
	private long keyCount;

	/** v3：关联笔记数（t_agent_resource 聚合，无主化后的关联语义） */
	private long noteCount;

	/** 最近使用时间（所有 key 中 max(last_used_at)） */
	private Date lastUsedAt;

	/** v3.5：该 agent 关联的笔记本 id 集合（t_agent_notebook） */
	private List<Long> notebookIds;

	/** v3.5：该 agent 关联的笔记本名集合（与 notebookIds 对齐） */
	private List<String> notebookNames;

	private Date createTime;
}
