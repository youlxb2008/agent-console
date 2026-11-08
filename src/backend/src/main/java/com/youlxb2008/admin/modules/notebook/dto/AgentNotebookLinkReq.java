package com.youlxb2008.admin.modules.notebook.dto;

import java.util.List;

import lombok.Data;

/**
 * agent→笔记本关联全量覆盖请求（v3.5 新增）
 * <p>
 * PUT /api/agent/admin/agents/{id}/notebooks，body={notebookIds:[]}。
 * 目标集合即最终态（幂等 replace）；空数组=清空该 agent 的全部笔记本关联。
 */
@Data
public class AgentNotebookLinkReq {

	/** 目标笔记本 id 集合（最终态，允许为空） */
	private List<Long> notebookIds;
}