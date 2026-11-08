package com.youlxb2008.admin.modules.note.dto;

import java.util.List;

import lombok.Data;

/**
 * 管理端：笔记关联全量覆盖请求（v3 新增）
 * <p>
 * PUT /api/note/admin/notes/{nid}/agents 入参：目标 agent 集合即最终态
 * （全量覆盖语义，空数组=清空全部关联）；不存在的 agentId 由 Controller 过滤。
 */
@Data
public class NoteAgentsUpdateReq {

	/** 目标 agent id 集合（最终态；null 视为清空） */
	private List<Long> agentIds;
}
