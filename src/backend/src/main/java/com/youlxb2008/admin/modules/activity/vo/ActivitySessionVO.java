package com.youlxb2008.admin.modules.activity.vo;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 会话话题聚合 VO（M6.2，门户话题列表行，服务端计算）
 * <p>
 * 契约字段：sessionKey/topicSummary/turnCount/toolCallCount/errorCount/totalActiveMs/lastEventTime/flags。
 * flags 为确定性诊断规则结果（读时计算不落表），固定顺序：
 * loop_suspect / long_turn / long_tool / error_prone / stalled（无异常为空列表）。
 */
@Data
public class ActivitySessionVO {

	/** 会话 key（如 websocket:7257d081-...） */
	private String sessionKey;

	/** 话题摘要（该会话最早 turn_started 的 summary；无 turn_started 时 null） */
	private String topicSummary;

	/** turn_started 事件数 */
	private Integer turnCount;

	/** tool_call 事件数 */
	private Integer toolCallCount;

	/** outcome=failed 的 tool_call 事件数 */
	private Integer errorCount;

	/** durationMs 合计（毫秒） */
	private Long totalActiveMs;

	/** 最后事件时间（yyyy-MM-dd HH:mm:ss） */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date lastEventTime;

	/** 诊断 flags（固定顺序，无异常为空列表） */
	private List<String> flags;

	/**
	 * 诊断依据（可读性优化，flag → 人话解释，如 loop_suspect →
	 * "同参数工具调用重复 5 次，如：git status"）；前端徽标 tooltip 展示。
	 * 与 flags 一一对应，无异常为空 map。
	 */
	private Map<String, String> flagDetails;
}
