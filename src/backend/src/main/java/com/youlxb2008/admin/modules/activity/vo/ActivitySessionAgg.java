package com.youlxb2008.admin.modules.activity.vo;

import java.util.Date;

import lombok.Data;

/**
 * 话题聚合统计行（M6.2，Mapper GROUP BY session_key 的中间结果，非对外契约 VO）
 * <p>
 * 由 {@code ActivityMapper.aggregateSessions} 一次 SQL 算出各会话的计数统计，
 * longToolCount/longTurnCount 为超限事件计数（0 = 无该 flag），lastId 供回表取最后事件类型。
 */
@Data
public class ActivitySessionAgg {

	/** 会话 key */
	private String sessionKey;

	/** turn_started 事件数 */
	private Long turnCount;

	/** tool_call 事件数 */
	private Long toolCallCount;

	/** outcome=failed 的 tool_call 事件数 */
	private Long errorCount;

	/** durationMs 超长工具阈值的事件数（tool_call） */
	private Long longToolCount;

	/** durationMs 超长回合阈值的事件数（turn_completed） */
	private Long longTurnCount;

	/** durationMs 合计（毫秒） */
	private Long totalActiveMs;

	/** 最长回合时长（turn_completed 的 MAX(duration_ms)，long_turn 依据展示用） */
	private Long maxTurnMs;

	/** 最长工具调用时长（tool_call 的 MAX(duration_ms)，long_tool 依据展示用） */
	private Long maxToolMs;

	/** 最后事件时间 */
	private Date lastEventTime;

	/** 最后事件 id（组内 MAX(id)，供回表取最后事件类型） */
	private Long lastId;
}
