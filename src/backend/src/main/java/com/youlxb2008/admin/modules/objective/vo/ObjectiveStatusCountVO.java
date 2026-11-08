package com.youlxb2008.admin.modules.objective.vo;

import lombok.Data;

/**
 * 目标状态计数 VO（门户 agent 详情页筛选胶囊 / Tab 角标用）
 * <p>
 * 全量口径：按 agent 统计所有未软删目标（不受 status 筛选影响），
 * 解决前端「基于已加载条目统计」导致的筛选后计数漂移问题。
 */
@Data
public class ObjectiveStatusCountVO {

	/** 全部（= 各状态之和） */
	private long total;

	/** 未开始（status=0） */
	private long pending;

	/** 进行中（status=1） */
	private long doing;

	/** 已完成（status=2） */
	private long done;

	/** 已放弃（status=3） */
	private long dropped;
}
