package com.youlxb2008.admin.modules.activity.dto;

import java.util.List;

import lombok.Data;

/**
 * agent 域：活动流批量上报请求（M6.1）
 * <p>
 * POST /api/agent/activity（scope activity:write）。
 * items 非空且 ≤ 50 条（超出 400，service 层校验）；eventType 白名单四值；
 * summary 服务端截断 512 字符兜底；其余可选字段 null 容忍。
 * agent_id 取自 key 身份（AgentContext），<b>不信任请求体</b>（DTO 无 agentId 字段）。
 * 插件侧 2s 一批 fail-open 丢弃不重试（无幂等要求，允许丢批）。
 */
@Data
public class ActivityBatchReq {

	/** 事件批次（1~50 条） */
	private List<ActivityItemReq> items;

	/**
	 * 单条事件（全部字段可选除 eventType；null 容忍）
	 */
	@Data
	public static class ActivityItemReq {

		/** 事件类型：turn_started/status_changed/tool_call/turn_completed（白名单） */
		private String eventType;

		/** 来源会话 key（可空） */
		private String sessionKey;

		/** 工具名（tool_call 时有值，可空） */
		private String toolName;

		/** 摘要（服务端截断 512 字符兜底，可空） */
		private String summary;

		/** 耗时毫秒（tool_call/turn_completed 时有值，可空） */
		private Integer durationMs;

		/** 结果：completed/failed/interrupted（turn_completed 时有值，可空） */
		private String outcome;
	}
}
