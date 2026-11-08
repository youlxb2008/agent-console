package com.youlxb2008.admin.modules.notebook.vo;

import java.util.Date;

import lombok.Data;

/**
 * 笔记本下已关联 agent 出参 VO（v3.5 新增）
 * <p>
 * GET /api/system/notebook/{id}/agents 列表项：agentId/name/displayName/linkTime，
 * display_name 优先回退 name；linkTime 取 t_agent_notebook.create_time。
 */
@Data
public class NotebookAgentVO {

	/** agent id */
	private Long agentId;

	/** agent 名（机器名） */
	private String name;

	/** 展示名（display_name 优先，无则回退 name） */
	private String displayName;

	/** 关联时间（t_agent_notebook.create_time） */
	private Date linkTime;
}