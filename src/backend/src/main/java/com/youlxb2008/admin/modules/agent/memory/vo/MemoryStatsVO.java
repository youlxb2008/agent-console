package com.youlxb2008.admin.modules.agent.memory.vo;

import lombok.Data;

/**
 * 记忆统计 VO（M7.1 M2 管理端，GET /api/memory/admin/memories/stats）
 * <p>
 * 按 agent 聚合的生命周期计数：活跃（1）/ 归档（2）/ 被取代（3）+ 总数。
 * 管理端统计卡片数据源（任务书 §3.1）。
 */
@Data
public class MemoryStatsVO {

	/** agent id */
	private Long agentId;

	/** agent 展示名（display_name 优先回退 name） */
	private String agentName;

	/** 活跃数（status=1） */
	private long activeCount;

	/** 归档数（status=2） */
	private long archivedCount;

	/** 被取代数（status=3） */
	private long supersededCount;

	/** 总数（未删除行） */
	private long totalCount;
}
