package com.youlxb2008.admin.modules.activity.vo;

import lombok.Data;

/**
 * loop_suspect 命中行（可读性优化，Mapper GROUP BY 的中间结果，非对外契约 VO）
 * <p>
 * {@code ActivityMapper.findLoopSuspectRows} 返回每个命中组（同会话内 tool_name +
 * summary 前缀相同且重复达阈值的工具调用组），Service 端按会话取重复次数最大的一组
 * 作为 flagDetails 依据（重复次数 + 示例摘要）。
 * <p>
 * 🔥 只统计 start 事件（outcome IS NULL 且 summary 非空）：v2 插件 end 事件 summary
 * 恒为 "ok"，曾把所有正常会话误判为 loop_suspect（(exec,"ok") 轻松 ≥3）；v3 插件 end
 * summary 为空串，双条件（outcome IS NULL + summary != ''）同时兼容存量与增量。
 */
@Data
public class LoopSuspectRow {

	/** 会话 key */
	private String sessionKey;

	/** 该组同参数调用次数 */
	private Long repeatCount;

	/** 该组任一示例摘要（MIN(summary)，供 flagDetails 展示） */
	private String sampleSummary;
}
