package com.youlxb2008.admin.modules.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * agent 域/管理端：记一笔台账请求（M4.2）
 * <p>
 * POST /api/agent/ledger（agent 域，scope ledger:write）/ POST /api/ledger/admin/ledger?agentId=（管理端）。
 * type/title 必填；body 可选（≤500 字存 body_inline，>500 字写 md 存 body_path）；
 * ref 四列可选（事件归因单选引用列，可并存：refTaskId / refRequirementId / refObjectiveId / refNoteNid）。
 */
@Data
public class LedgerCreateReq {

	/** 类型：progress/observation/decision/memo/session（必填） */
	@NotBlank(message = "type 不能为空")
	private String type;

	/** 条目标题（必填，≤200 字符） */
	@NotBlank(message = "title 不能为空")
	private String title;

	/** 正文（可选；≤500 字存 inline，>500 字写 md） */
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
