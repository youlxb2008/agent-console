package com.youlxb2008.admin.modules.activity.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 活动流当前状态聚合 VO（M6.1，服务端计算，门户 HomeView 徽标数据源）
 * <p>
 * running 判定：该 agent 最近一条事件非 turn_completed 且最后事件时间距今 ≤ 10 分钟；
 * 否则 idle。10 分钟兜底：插件失联/agent 容器重启后不许永远「执行中」。
 * sinceMs 统一 = 距最后一条事件毫秒数（running=当前状态已持续时长；idle=距最后活动时长）。
 */
@Data
public class ActivityCurrentVO {

	/** 当前状态：running / idle */
	private String state;

	/** 正在执行的工具名（running 且最后事件为 tool_call 时有值） */
	private String toolName;

	/** 距最后一条事件毫秒数（running=状态持续时长；idle=距最后活动时长） */
	private Long sinceMs;

	/** 最近一次 turn_completed 的结果：completed/failed/interrupted（无完成事件时 null） */
	private String lastOutcome;

	/** 最后一条事件摘要 */
	private String lastSummary;

	/** 最后一条事件时间（yyyy-MM-dd HH:mm:ss） */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date lastEventTime;
}
