package com.youlxb2008.admin.modules.agent.vo;

import java.util.Date;

import lombok.Data;

/**
 * 门户：agent 概览卡（契约 api.md §3，GET /api/portal/agents）
 * <p>
 * 字段白名单：id/name/displayName/status/noteCount/lastNoteAt，
 * <b>绝不暴露 key_hash/scopes/审计/IP</b>。仅 status=1 的 agent。
 */
@Data
public class PortalAgentVO {

	/** agent id（深链接 ?agentId= 用） */
	private Long id;

	private String name;

	private String displayName;

	/** v4.3：头像 URL（/uploads/... 相对路径；null=无头像，门户色块兜底） */
	private String avatar;

	/** v3：笔记本名（=display_name 优先回退 name，门户"笔记本"文案专用） */
	private String notebookName;

	/** 恒为 1（仅返回 active agent） */
	private Integer status;

	/** 笔记数（v3：t_agent_resource 关联聚合） */
	private Long noteCount;

	/** 最近一篇笔记更新时间（v3：关联 JOIN t_note_meta 聚合） */
	private Date lastNoteAt;

}
