package com.youlxb2008.admin.modules.ledger.dto;

import lombok.Data;

/**
 * 台账部分更新请求（M4.2，PATCH）
 * <p>
 * PATCH /api/agent/ledger/{lid}（agent 域）/ PATCH /api/ledger/admin/ledger/{lid}（管理端）。
 * 全部字段可空（=不修改该项）；「至少一项非空」由 service 层判断（全空 → 400）。
 * body 提供（含空串）则覆写正文并按双轨重新切分（inline↔md 互转，原 md 文件保留不删）。
 */
@Data
public class LedgerPatchReq {

	/** 类型（可选；5 种枚举之一） */
	private String type;

	/** 条目标题（可选，≤200 字符） */
	private String title;

	/** 正文覆写（可选；null=不改，非 null=覆写并按双轨切分） */
	private String body;

	/** 关联任务 tid（可选） */
	private String refTaskId;

	/** 关联需求 rid（可选） */
	private String refRequirementId;

	/** 关联目标 oid（可选） */
	private String refObjectiveId;

	/** 关联笔记 nid（可选） */
	private String refNoteNid;
}
