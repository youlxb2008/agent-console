package com.youlxb2008.admin.modules.activity.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 实时活动流实体（M6.1，任务书 docs/任务排期/M6.1-activity-stream.md）
 * 对应数据库表 t_activity。
 * <p>
 * 与 todo/objective 等模块刻意不同：
 * <ul>
 *   <li>纯流水表：只追加不修改不软删（无 is_deleted/@TableLogic、无 update_time），
 *       保留策略靠 {@code ActivityCleanupJob} 每日物理清理 30 天前数据</li>
 *   <li>无对外引用键（id 自增即对外展示键，门户只读倒序流）</li>
 *   <li>归属：agent_id 单值，由上报接口从 key 身份（AgentContext）注入，不信任请求体</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_activity")
public class Activity {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（门户时间线展示键） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属 agent（t_agent.id），由 key 身份注入 */
	private Long agentId;

	/** 来源会话 key（如 websocket:xxx），可空 */
	private String sessionKey;

	/** 事件类型：turn_started/status_changed/tool_call/turn_completed（白名单四值） */
	private String eventType;

	/** 工具名（tool_call 时有值） */
	private String toolName;

	/** 摘要（服务端截断 512 字符兜底） */
	private String summary;

	/** 耗时毫秒（tool_call/turn_completed 时有值） */
	private Integer durationMs;

	/** 结果：completed/failed/interrupted（turn_completed 时有值） */
	private String outcome;

	/** 事件时间（入库时间即事件时间，插件 2s 一批准实时） */
	private Date createTime;
}
