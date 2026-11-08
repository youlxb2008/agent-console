package com.youlxb2008.admin.modules.notebook.dto;

import java.util.List;

import lombok.Data;

/**
 * 笔记本→agent 关联全量覆盖请求（v3.5 新增）
 * <p>
 * PUT /api/system/notebook/{id}/agents，body={agentIds:[]}。
 * 目标集合即最终态（幂等 replace）；空数组=清空全部关联。
 */
@Data
public class NotebookAgentLinkReq {

	/** 目标 agent id 集合（最终态，允许为空） */
	private List<Long> agentIds;
}