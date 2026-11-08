package com.youlxb2008.admin.modules.ledger.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 台账列表项 VO（M4.2，agent 域/管理端/门户共用）
 * <p>
 * 只读索引字段（不读文件、不含正文）：lid/type/title/时间/引用列。
 * 详情接口另行读正文（见 {@link LedgerDetailVO}）。
 */
@Data
public class LedgerVO {

	/** 对外引用键 l_xxx */
	private String lid;

	/** 归属 agent id（管理端/门户聚合回填；agent 域私域不返回） */
	private Long agentId;

	/** 归属 agent 展示名（管理端/门户聚合回填；display_name 优先回退 name） */
	private String agentName;

	/** 类型：progress/observation/decision/memo/session */
	private String type;

	/** 条目标题 */
	private String title;

	/** 关联任务 tid（可选） */
	private String refTaskId;

	/** 关联需求 rid（可选） */
	private String refRequirementId;

	/** 关联目标 oid（可选） */
	private String refObjectiveId;

	/** 关联笔记 nid（可选） */
	private String refNoteNid;

	/** 创建时间（流水时间） */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;
}
