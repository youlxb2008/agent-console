package com.youlxb2008.admin.modules.activity.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.activity.entity.Activity;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionAgg;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionRef;
import com.youlxb2008.admin.modules.activity.vo.LoopSuspectRow;

/**
 * 活动流 Mapper（M6.1，t_activity）
 * <p>
 * 纯 BaseMapper：列表/聚合用 LambdaQueryWrapper（无软删列，无需 @TableLogic 过滤）；
 * 保留期清理用 delete(LambdaWrapper)。
 * <p>
 * M6.2 话题聚合（跨行 GROUP BY 统计）以 @Select 注解实现（照 AgentResourceMapper/NotebookMapper
 * 先例，不引入 XML），全部参数化查询防注入；超限阈值由 Service 以参数传入（常量集中在 ActivityService）。
 */
public interface ActivityMapper extends BaseMapper<Activity> {

	/**
	 * M6.2 会话话题分组统计：按 session_key 聚合计数 + 超限事件计数 + 最后事件时间/id。
	 * 只统计 session_key 非空的行；按最近活跃倒序（lastEventTime DESC，同秒 lastId DESC）。
	 *
	 * @param agentId    agent id
	 * @param limit      返回会话数（调用方已钳制）
	 * @param longToolMs long_tool 阈值毫秒
	 * @param longTurnMs long_turn 阈值毫秒
	 * @return 分组统计行（按最近活跃倒序）
	 */
	@Select("SELECT session_key AS sessionKey, "
			+ "SUM(CASE WHEN event_type = 'turn_started' THEN 1 ELSE 0 END) AS turnCount, "
			+ "SUM(CASE WHEN event_type = 'tool_call' THEN 1 ELSE 0 END) AS toolCallCount, "
			+ "SUM(CASE WHEN event_type = 'tool_call' AND outcome = 'failed' THEN 1 ELSE 0 END) AS errorCount, "
			+ "SUM(CASE WHEN event_type = 'tool_call' AND duration_ms > #{longToolMs} THEN 1 ELSE 0 END) AS longToolCount, "
			+ "SUM(CASE WHEN event_type = 'turn_completed' AND duration_ms > #{longTurnMs} THEN 1 ELSE 0 END) AS longTurnCount, "
			+ "SUM(duration_ms) AS totalActiveMs, "
			+ "MAX(CASE WHEN event_type = 'turn_completed' THEN duration_ms END) AS maxTurnMs, "
			+ "MAX(CASE WHEN event_type = 'tool_call' THEN duration_ms END) AS maxToolMs, "
			+ "MAX(create_time) AS lastEventTime, MAX(id) AS lastId "
			+ "FROM t_activity WHERE agent_id = #{agentId} AND session_key IS NOT NULL "
			+ "GROUP BY session_key ORDER BY lastEventTime DESC, lastId DESC LIMIT #{limit}")
	List<ActivitySessionAgg> aggregateSessions(@Param("agentId") Long agentId, @Param("limit") int limit,
			@Param("longToolMs") long longToolMs, @Param("longTurnMs") long longTurnMs);

	/**
	 * loop_suspect 检测（可读性优化重写）：同会话内 (tool_name, summary 全文) 相同的
	 * start 事件计数 ≥ minRepeat 的命中组（会话 key + 重复次数 + 示例摘要）。
	 * <p>
	 * 🔥 只统计 start 事件（outcome IS NULL 且 summary 非空）：v2 插件 end 事件 summary
	 * 恒为 "ok"，(exec,"ok") 轻松 ≥3，曾把所有正常会话误判为 loop_suspect；v3 插件 end
	 * summary 为空串，双条件同时兼容存量与增量。空 summary 的无参调用不参与比对。
	 * <p>
	 * 🔥 比对口径 summary 前 64 字符 → 全文：前缀判定存在样板碰撞误报——exec 命令惯用
	 * 样板开头（source ~/secrets.env / cd ~/workspace / B64=$(...) 等）落在前 64 字符内，
	 * 不同命令同前缀即被误判循环（prod 实测单会话 35 次误命中）；全文判定只抓"完全相同
	 * 命令重复"，真循环（同命令反复重试）不受影响。
	 * <p>
	 * ⚠️ 插件 start 摘要截断 160 字符，长命令差异在截断点之后时摘要仍相同——
	 * 纯重复判定无法根治此类碰撞，Service 侧另加"会话 failed ≥3"失败佐证
	 * （正常批量查询无失败不报；真循环反复重试必带失败）。
	 *
	 * @param agentId   agent id
	 * @param minRepeat 判定循环的最小重复次数
	 * @return 命中组列表（同会话可能多组；Service 取重复次数最大的一组作依据）
	 */
	@Select("SELECT session_key AS sessionKey, COUNT(*) AS repeatCount, MIN(summary) AS sampleSummary "
			+ "FROM t_activity "
			+ "WHERE agent_id = #{agentId} AND event_type = 'tool_call' AND outcome IS NULL "
			+ "AND session_key IS NOT NULL AND summary IS NOT NULL AND summary != '' "
			+ "GROUP BY session_key, tool_name, summary "
			+ "HAVING COUNT(*) >= #{minRepeat}")
	List<LoopSuspectRow> findLoopSuspectRows(@Param("agentId") Long agentId, @Param("minRepeat") int minRepeat);

	/**
	 * M6.2 话题摘要回表：每个会话最近一条 turn_started（MAX(id)）的 summary。
	 * <p>
	 * 🔥 取「最近」而非「最早」（2026-09-28 修正）：通道会话 key 复用（websocket/qq
	 * 同一会话跨天续用），最早 turn_started 是数天前旧任务标题，话题卡片永远顶着
	 * 旧话题——复用会话应展示当前正在做的事。
	 * 子查询全 agent 范围 GROUP BY 后 JOIN 回表，行数 = 会话数，无动态 IN。
	 *
	 * @param agentId agent id
	 * @return sessionKey + summary（话题摘要）
	 */
	@Select("SELECT a.session_key AS sessionKey, a.summary FROM t_activity a "
			+ "JOIN (SELECT session_key, MAX(id) AS tail_id FROM t_activity "
			+ "WHERE agent_id = #{agentId} AND event_type = 'turn_started' AND session_key IS NOT NULL "
			+ "GROUP BY session_key) t ON a.id = t.tail_id")
	List<ActivitySessionRef> findLastTurnStarted(@Param("agentId") Long agentId);

	/**
	 * M6.2 最后事件类型回表：每个会话最后一条事件（MAX(id)）的 event_type（stalled 判定用）。
	 *
	 * @param agentId agent id
	 * @return sessionKey + eventType（最后事件类型）
	 */
	@Select("SELECT a.session_key AS sessionKey, a.event_type AS eventType FROM t_activity a "
			+ "JOIN (SELECT session_key, MAX(id) AS tail_id FROM t_activity "
			+ "WHERE agent_id = #{agentId} AND session_key IS NOT NULL "
			+ "GROUP BY session_key) t ON a.id = t.tail_id")
	List<ActivitySessionRef> findLastEventTypes(@Param("agentId") Long agentId);

	/**
	 * 最近错误样本回表（可读性优化）：每个会话最后一条 failed tool_call（MAX(id)）的 summary，
	 * 供 error_prone flagDetails 展示（"N 次工具失败，最近：…"）。
	 *
	 * @param agentId agent id
	 * @return sessionKey + summary（最近错误文本）
	 */
	@Select("SELECT a.session_key AS sessionKey, a.summary FROM t_activity a "
			+ "JOIN (SELECT session_key, MAX(id) AS tail_id FROM t_activity "
			+ "WHERE agent_id = #{agentId} AND event_type = 'tool_call' AND outcome = 'failed' AND session_key IS NOT NULL "
			+ "GROUP BY session_key) t ON a.id = t.tail_id")
	List<ActivitySessionRef> findLastErrorSamples(@Param("agentId") Long agentId);

	/**
	 * 话题摘要兜底回表（可读性优化）：每个会话最近一条 summary 非空事件（MAX(id)）。
	 * turn_started 无文本（定时任务会话）时，用最近有内容的事件摘要兜底，
	 * 替代前端无信息的「(无话题摘要)」；与 findLastTurnStarted 同口径取最近，
	 * 跨天续用的会话不展示数天前的旧摘要。
	 *
	 * @param agentId agent id
	 * @return sessionKey + summary（兜底话题摘要）
	 */
	@Select("SELECT a.session_key AS sessionKey, a.summary FROM t_activity a "
			+ "JOIN (SELECT session_key, MAX(id) AS tail_id FROM t_activity "
			+ "WHERE agent_id = #{agentId} AND session_key IS NOT NULL AND summary IS NOT NULL AND summary != '' "
			+ "GROUP BY session_key) t ON a.id = t.tail_id")
	List<ActivitySessionRef> findLastNonEmptySummary(@Param("agentId") Long agentId);
}

