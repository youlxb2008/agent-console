package com.youlxb2008.admin.modules.todo.vo;

import lombok.Data;

/**
 * 待办状态计数 VO（门户 agent 详情页 Tab 角标用）
 * <p>
 * 全量口径：按 agent 统计所有未软删待办（不受 status 筛选影响）。
 * 状态语义与目标域一致（0/1/2/3），仅 status=0 文案不同（待办 vs 未开始）。
 */
@Data
public class TodoStatusCountVO {

	/** 全部（= 各状态之和） */
	private long total;

	/** 待办（status=0） */
	private long pending;

	/** 进行中（status=1） */
	private long doing;

	/** 已完成（status=2） */
	private long done;

	/** 已放弃（status=3） */
	private long dropped;
}
