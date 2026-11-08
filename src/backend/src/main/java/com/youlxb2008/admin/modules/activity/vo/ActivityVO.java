package com.youlxb2008.admin.modules.activity.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 活动流 VO（M6.1，门户时间线列表项）
 * <p>
 * 契约字段：id/eventType/toolName/summary/durationMs/outcome/sessionKey/createTime。
 * 不暴露 agentId（路径已含 agentId，无跨 agent 泄露面）。
 */
@Data
public class ActivityVO {

	/** 事件 id（自增，时间线展示键） */
	private Long id;

	/** 事件类型：turn_started/status_changed/tool_call/turn_completed */
	private String eventType;

	/** 工具名（tool_call 时有值） */
	private String toolName;

	/** 摘要 */
	private String summary;

	/** 耗时毫秒（tool_call/turn_completed 时有值） */
	private Integer durationMs;

	/** 结果：completed/failed/interrupted（turn_completed 时有值） */
	private String outcome;

	/** 来源会话 key */
	private String sessionKey;

	/** 事件时间（yyyy-MM-dd HH:mm:ss） */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;
}
