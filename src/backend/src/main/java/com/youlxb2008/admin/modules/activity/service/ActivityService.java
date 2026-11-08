package com.youlxb2008.admin.modules.activity.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.modules.activity.dto.ActivityBatchReq;
import com.youlxb2008.admin.modules.activity.entity.Activity;
import com.youlxb2008.admin.modules.activity.mapper.ActivityMapper;
import com.youlxb2008.admin.modules.activity.vo.ActivityCurrentVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityDailyStatVO;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionAgg;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionRef;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityVO;
import com.youlxb2008.admin.modules.activity.vo.LoopSuspectRow;
import com.youlxb2008.admin.modules.agent.service.AgentService;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动流 Service（M6.1，agent 实时状态上报与门户展示；M6.2 扩展话题聚合与诊断）
 * <p>
 * 纯流水表（t_activity）：只追加不修改不软删，保留策略靠 ActivityCleanupJob 每日物理清理。
 * 核心语义（任务书 M6.1-activity-stream.md）：
 * <ul>
 *   <li>批量上报：items 非空且 ≤ 50 条（超出 400）；eventType 白名单四值；
 *       summary 服务端截断 512 字符兜底（sessionKey/toolName/outcome 防御性截断防脏数据 500）；
 *       agent_id 由 Controller 从 key 身份注入，不信任请求体</li>
 *   <li>入库失败整体 500，插件侧 fail-open 丢弃不重试（无幂等要求，允许丢批）</li>
 *   <li>列表：create_time 倒序（同时间 id 倒序），limit 默认 50、钳制 ≤ 200</li>
 *   <li>current 聚合（服务端计算）：running = 最近一条事件非 turn_completed 且距今 ≤ 10 分钟；
 *       否则 idle（10 分钟兜底：插件失联/容器重启后不许永远「执行中」）</li>
 *   <li>agent 不存在/已停用：列表返回空、current 返回 idle（不抛 404，防枚举）</li>
 * </ul>
 */
@Slf4j
@Service
public class ActivityService extends ServiceImpl<ActivityMapper, Activity> {

	/** 事件类型白名单（契约四值） */
	private static final Set<String> EVENT_TYPES = Set.of(
			"turn_started", "status_changed", "tool_call", "turn_completed");

	/** 批量上限（超出 400） */
	private static final int MAX_BATCH_SIZE = 50;
	/** summary 截断兜底（DDL VARCHAR(512)） */
	private static final int MAX_SUMMARY_LEN = 512;
	/** sessionKey 截断兜底（DDL VARCHAR(128)） */
	private static final int MAX_SESSION_KEY_LEN = 128;
	/** toolName 截断兜底（DDL VARCHAR(64)） */
	private static final int MAX_TOOL_NAME_LEN = 64;
	/** outcome 截断兜底（DDL VARCHAR(16)） */
	private static final int MAX_OUTCOME_LEN = 16;
	/** 列表 limit 默认值 */
	private static final int LIST_DEFAULT_LIMIT = 50;
	/** 列表 limit 上限 */
	private static final int LIST_MAX_LIMIT = 200;
	/** running 判定窗口：最后事件距今 ≤ 10 分钟 */
	private static final long RUNNING_WINDOW_MS = 10L * 60 * 1000;

	/** turn_completed 事件类型常量 */
	private static final String EVENT_TURN_COMPLETED = "turn_completed";
	/** turn_started 事件类型常量 */
	private static final String EVENT_TURN_STARTED = "turn_started";
	/** status_changed 事件类型常量 */
	private static final String EVENT_STATUS_CHANGED = "status_changed";
	/** tool_call 事件类型常量 */
	private static final String EVENT_TOOL_CALL = "tool_call";

	/** dream 会话前缀（记忆整合回合，每 2h 一轮；回合不发 turn_completed、summary 为空） */
	public static final String DREAM_SESSION_PREFIX = "dream:";
	/** dream 会话固定话题摘要（listSessions 覆盖 topics 查找结果） */
	public static final String DREAM_SESSION_LABEL = "记忆整合（dream）";

	// ============================================================
	// M6.2 诊断规则阈值（集中定义便于调参；契约 §1.13 flags 语义）
	// ============================================================

	/** loop_suspect：同话题内 (tool_name, summary 全文) 相同的 tool_call ≥ 3 次 */
	public static final int LOOP_MIN_REPEAT = 3;
	/** long_turn：turn_completed 的 durationMs 超限（10 分钟） */
	public static final long LONG_TURN_MS = 600_000L;
	/** long_tool：tool_call 的 durationMs 超限（2 分钟） */
	public static final long LONG_TOOL_MS = 120_000L;
	/** error_prone：同话题 outcome=failed 的 tool_call ≥ 3 次 */
	public static final int ERROR_PRONE_MIN = 3;
	/** stalled：话题最后事件为 turn_started/status_changed 且距今超窗（10 分钟，与 current 的 running 判定同窗，运行中但无推进） */
	public static final long STALLED_WINDOW_MS = 600_000L;
	/** 话题聚合 limit 默认值 */
	public static final int SESSIONS_DEFAULT_LIMIT = 20;
	/** 话题聚合 limit 上限 */
	public static final int SESSIONS_MAX_LIMIT = 50;

	/** 按天聚合 days 默认值（任务书约定 14） */
	public static final int STATS_DEFAULT_DAYS = 14;
	/** 按天聚合 days 钳制上限（任务书约定 31） */
	public static final int STATS_MAX_DAYS = 31;
	/** 按天聚合 days 钳制下限（任务书约定 1） */
	public static final int STATS_MIN_DAYS = 1;
	/** 按天聚合墙钟时区：create_time 存事件墙钟，jdbc serverTimezone=Asia/Shanghai 解码，窗口与分组均按此时区 */
	public static final ZoneId STATS_ZONE = ZoneId.of("Asia/Shanghai");
	/** statDate 格式（yyyy-MM-dd，按 STATS_ZONE 墙钟取日期） */
	private static final DateTimeFormatter STATS_DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(STATS_ZONE);

	@Autowired
	private AgentService agentService;

	// ============================================================
	// 写操作：批量上报
	// ============================================================

	/**
	 * 批量上报活动事件（契约 POST /api/agent/activity）。
	 * 校验：items 非空且 ≤ 50；eventType 白名单；summary 截断 512。
	 * agent_id 由调用方（Controller）从 key 身份传入，不信任请求体。
	 *
	 * @param agentId 归属 agent id（key 身份）
	 * @param req     批量请求
	 * @return 受理条数（accepted）
	 */
	public int report(Long agentId, ActivityBatchReq req) {
		List<ActivityBatchReq.ActivityItemReq> items = req == null ? null : req.getItems();
		if (items == null || items.isEmpty()) {
			throw new BadRequestException("items 不能为空");
		}
		if (items.size() > MAX_BATCH_SIZE) {
			throw new BadRequestException("单批最多 " + MAX_BATCH_SIZE + " 条");
		}
		Date now = new Date();
		List<Activity> rows = items.stream().map(item -> {
			String eventType = item.getEventType();
			if (eventType == null || !EVENT_TYPES.contains(eventType)) {
				throw new BadRequestException("eventType 取值须为 " + EVENT_TYPES + " 之一");
			}
			Activity row = new Activity();
			row.setAgentId(agentId);
			row.setEventType(eventType);
			row.setSessionKey(truncate(item.getSessionKey(), MAX_SESSION_KEY_LEN));
			row.setToolName(truncate(item.getToolName(), MAX_TOOL_NAME_LEN));
			row.setSummary(truncate(item.getSummary(), MAX_SUMMARY_LEN));
			row.setDurationMs(item.getDurationMs());
			row.setOutcome(truncate(item.getOutcome(), MAX_OUTCOME_LEN));
			row.setCreateTime(now);
			return row;
		}).toList();
		saveBatch(rows);
		return rows.size();
	}

	// ============================================================
	// 读操作：门户列表 / current 聚合
	// ============================================================

	/**
	 * 某 agent 活动流列表（create_time 倒序，同时间 id 倒序；limit 默认 50、钳制 ≤ 200）。
	 * agent 不存在/已停用 → 空列表（照 portal 接口容错风格，不抛 404 防枚举）。
	 *
	 * @param agentId agent id
	 * @param limit   条数（null/非正 → 默认 50）
	 * @return 活动流 VO 列表（倒序）
	 */
	public List<ActivityVO> listByAgent(Long agentId, Integer limit) {
		return listByAgent(agentId, limit, null);
	}

	/**
	 * 某 agent 活动流列表（M6.2 增加可选 sessionKey 精确过滤，话题轨迹页拉单会话事件序列）。
	 * sessionKey 空白视为不过滤；过滤值走 LambdaQuery 参数化查询，无注入面。
	 *
	 * @param agentId    agent id
	 * @param limit      条数（null/非正 → 默认 50）
	 * @param sessionKey 会话 key 精确过滤（null/空白 → 不过滤）
	 * @return 活动流 VO 列表（倒序）
	 */
	public List<ActivityVO> listByAgent(Long agentId, Integer limit, String sessionKey) {
		if (!isAgentVisible(agentId)) {
			return List.of();
		}
		int eff = limit == null || limit <= 0 ? LIST_DEFAULT_LIMIT : Math.min(limit, LIST_MAX_LIMIT);
		var wrapper = Wrappers.<Activity>lambdaQuery()
				.eq(Activity::getAgentId, agentId)
				.orderByDesc(Activity::getCreateTime)
				.orderByDesc(Activity::getId)
				.last("LIMIT " + eff);
		if (sessionKey != null && !sessionKey.isBlank()) {
			wrapper.eq(Activity::getSessionKey, sessionKey.trim());
		}
		List<Activity> rows = list(wrapper);
		return rows.stream().map(this::toVO).toList();
	}

	/**
	 * 某 agent 当前状态聚合（服务端计算，契约 GET /api/portal/agents/{agentId}/activity/current）。
	 * <p>
	 * running 判定：最近一条事件非 turn_completed 且最后事件时间距今 ≤ 10 分钟；否则 idle。
	 * sinceMs = 距最后一条事件毫秒数；lastSummary = 最后一条事件摘要；
	 * lastOutcome = 最近一次 turn_completed 的 outcome（回溯查询，覆盖「失联前最后结果」语义）。
	 * agent 不存在/已停用/无事件 → idle 空态（不抛 404，防枚举）。
	 *
	 * @param agentId agent id
	 * @return 当前状态聚合 VO
	 */
	public ActivityCurrentVO currentByAgent(Long agentId) {
		ActivityCurrentVO vo = new ActivityCurrentVO();
		vo.setState("idle");
		Activity last = lastEvent(agentId);
		if (last == null) {
			return vo;
		}
		Date now = new Date();
		long sinceMs = Math.max(0L, now.getTime() - last.getCreateTime().getTime());
		vo.setSinceMs(sinceMs);
		vo.setLastSummary(last.getSummary());
		vo.setLastEventTime(last.getCreateTime());
		boolean running = !EVENT_TURN_COMPLETED.equals(last.getEventType()) && sinceMs <= RUNNING_WINDOW_MS;
		if (running) {
			vo.setState("running");
			// running 且最后事件为 tool_call 时回显工具名
			if (EVENT_TOOL_CALL.equals(last.getEventType())) {
				vo.setToolName(last.getToolName());
			}
		} else {
			// idle：回溯最近一次 turn_completed 的 outcome（无则 null）
			Activity lastDone = getOne(Wrappers.<Activity>lambdaQuery()
					.eq(Activity::getAgentId, agentId)
					.eq(Activity::getEventType, EVENT_TURN_COMPLETED)
					.orderByDesc(Activity::getCreateTime)
					.orderByDesc(Activity::getId)
					.last("LIMIT 1"), false);
			vo.setLastOutcome(lastDone != null ? lastDone.getOutcome() : null);
		}
		return vo;
	}

	// ============================================================
	// M6.2 读操作：话题聚合 + 确定性诊断（读时计算，不落表）
	// ============================================================

	/**
	 * 话题聚合列表（M6.2，契约 GET /api/portal/agents/{agentId}/activity/sessions）。
	 * <p>
	 * 按 session_key 分组统计（Mapper 一次 GROUP BY 出计数/超限/最后事件），flags 为读时
	 * 计算的确定性诊断（不落表，阈值常量集中本类），固定顺序：loop_suspect / long_turn /
	 * long_tool / error_prone / stalled；flagDetails 为每个 flag 的人话依据（可读性优化，
	 * 前端徽标 tooltip）。话题摘要 = 该会话最近 turn_started 的 summary（通道会话 key
	 * 复用跨天续用，取最近展示当前话题而非数天前旧标题），为空时兜底取最近一条
	 * 非空事件摘要（定时任务会话不再显示无信息的空摘要）。
	 * dream:* 会话（记忆整合回合，回合不发 turn_completed）话题摘要固定为
	 * DREAM_SESSION_LABEL（覆盖 topics 查找结果），且诊断跳过 stalled（恒误报）。
	 * agent 不存在/已停用 → 空列表（防枚举，与 M6.1 口径一致）。
	 *
	 * @param agentId agent id
	 * @param limit   会话数（null/非正 → 默认 20，钳制 ≤ 50）
	 * @return 话题聚合 VO 列表（按最近活跃倒序）
	 */
	public List<ActivitySessionVO> listSessions(Long agentId, Integer limit) {
		if (!isAgentVisible(agentId)) {
			return List.of();
		}
		int eff = limit == null || limit <= 0 ? SESSIONS_DEFAULT_LIMIT : Math.min(limit, SESSIONS_MAX_LIMIT);
		List<ActivitySessionAgg> aggs = baseMapper.aggregateSessions(agentId, eff, LONG_TOOL_MS, LONG_TURN_MS);
		if (aggs.isEmpty()) {
			return List.of();
		}
		// 话题摘要回表：每组最近 turn_started 的 summary（无 turn_started 的会话 → null）
		Map<String, String> topics = new HashMap<>();
		for (ActivitySessionRef ref : baseMapper.findLastTurnStarted(agentId)) {
			topics.putIfAbsent(ref.getSessionKey(), ref.getSummary());
		}
		// 话题摘要兜底：turn_started 无文本（定时任务会话）→ 最近一条非空事件摘要
		for (ActivitySessionRef ref : baseMapper.findLastNonEmptySummary(agentId)) {
			String cur = topics.get(ref.getSessionKey());
			if (cur == null || cur.isBlank()) {
				topics.put(ref.getSessionKey(), ref.getSummary());
			}
		}
		// 最后事件类型回表：stalled 判定用
		Map<String, String> lastTypes = new HashMap<>();
		for (ActivitySessionRef ref : baseMapper.findLastEventTypes(agentId)) {
			lastTypes.putIfAbsent(ref.getSessionKey(), ref.getEventType());
		}
		// loop_suspect 命中组：同会话取重复次数最大的一组作依据
		Map<String, LoopSuspectRow> loopRows = new HashMap<>();
		for (LoopSuspectRow row : baseMapper.findLoopSuspectRows(agentId, LOOP_MIN_REPEAT)) {
			loopRows.merge(row.getSessionKey(), row,
					(a, b) -> longValue(a.getRepeatCount()) >= longValue(b.getRepeatCount()) ? a : b);
		}
		// 最近错误样本回表：error_prone 依据
		Map<String, String> errorSamples = new HashMap<>();
		for (ActivitySessionRef ref : baseMapper.findLastErrorSamples(agentId)) {
			errorSamples.putIfAbsent(ref.getSessionKey(), ref.getSummary());
		}
		Date now = new Date();
		List<ActivitySessionVO> result = new ArrayList<>(aggs.size());
		for (ActivitySessionAgg agg : aggs) {
			ActivitySessionVO vo = new ActivitySessionVO();
			vo.setSessionKey(agg.getSessionKey());
			// dream 会话（记忆整合回合）：回合不发 turn_completed、summary 为空，
			// 固定标签覆盖 topics 查找结果（避免空摘要）
			vo.setTopicSummary(isDreamSession(agg.getSessionKey())
					? DREAM_SESSION_LABEL : topics.get(agg.getSessionKey()));
			vo.setTurnCount(intValue(agg.getTurnCount()));
			vo.setToolCallCount(intValue(agg.getToolCallCount()));
			vo.setErrorCount(intValue(agg.getErrorCount()));
			vo.setTotalActiveMs(agg.getTotalActiveMs());
			vo.setLastEventTime(agg.getLastEventTime());
			Map<String, String> flagDetails = new LinkedHashMap<>();
			vo.setFlags(diagnose(agg, lastTypes.get(agg.getSessionKey()), loopRows, errorSamples, now, flagDetails));
			vo.setFlagDetails(flagDetails);
			result.add(vo);
		}
		return result;
	}

	// ============================================================
	// v4.18 智能体页「概览」仪表盘（任务书 §2）
	// ============================================================

	/**
	 * v4.18 某 agent 活动流按天聚合（匿名只读，任务书
	 * GET /api/portal/agents/{agentId}/activity/stats）。
	 * <p>
	 * 仅按 {@code agent_id} 过滤，窗口 = [Asia/Shanghai 今天零点 -(days-1) 天, +∞)；
	 * 返回按日期升序的逐日聚合行，窗口内无事件的日子不出现在列表里（前端按
	 * statDate 补 gap 灰色斜纹柱）。days 钳制 {@value #STATS_MIN_DAYS} ~
	 * {@value #STATS_MAX_DAYS}，默认 {@value #STATS_DEFAULT_DAYS}（null/非正走
	 * 默认）；实际生效值统一走 {@link #resolveStatsDays(Integer)}，Controller
	 * 响应里的 days 与此同源不重复实现。
	 * <p>
	 * agent 不存在/已停用 → 空列表（与 listSessions 同口径，防枚举）。
	 * <p>
	 * 跨库兼容（评审打回修正）：H2 专有 FORMATDATETIME 真 MySQL 不存在（部署即
	 * 500），改为 lambdaQuery 只取 create_time/event_type/session_key 三列，
	 * Java 侧按墙钟日期分组——create_time 存的即事件墙钟，驱动按
	 * serverTimezone=Asia/Shanghai 解码后再以 {@link #STATS_ZONE} 取 yyyy-MM-dd，
	 * 与 SQL 侧 DATE(create_time) 墙钟口径一致；31 天窗口行量无压力，
	 * events/sessions(COUNT DISTINCT)/tools/turns 四项语义不变。
	 *
	 * @param agentId agent id
	 * @param days    窗口天数（null/非正 → 默认 14；钳制 1~31）
	 * @return 按日期升序的每日聚合行
	 */
	public List<ActivityDailyStatVO> statsByAgent(Long agentId, Integer days) {
		if (!isAgentVisible(agentId)) {
			return List.of();
		}
		int eff = resolveStatsDays(days);
		Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(STATS_ZONE));
		cal.add(Calendar.DAY_OF_MONTH, -(eff - 1));
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		Date since = cal.getTime();
		List<Activity> rows = list(Wrappers.<Activity>lambdaQuery()
				.select(Activity::getCreateTime, Activity::getEventType, Activity::getSessionKey)
				.eq(Activity::getAgentId, agentId)
				.ge(Activity::getCreateTime, since));
		Map<String, ActivityDailyStatVO> byDay = new TreeMap<>();
		Map<String, Set<String>> daySessions = new HashMap<>();
		for (Activity row : rows) {
			if (row.getCreateTime() == null) {
				continue;
			}
			String day = STATS_DAY_FMT.format(row.getCreateTime().toInstant());
			ActivityDailyStatVO vo = byDay.computeIfAbsent(day, key -> {
				ActivityDailyStatVO v = new ActivityDailyStatVO();
				v.setStatDate(key);
				v.setEvents(0L);
				v.setSessions(0L);
				v.setTools(0L);
				v.setTurns(0L);
				return v;
			});
			vo.setEvents(vo.getEvents() + 1);
			if (EVENT_TOOL_CALL.equals(row.getEventType())) {
				vo.setTools(vo.getTools() + 1);
			}
			if (EVENT_TURN_COMPLETED.equals(row.getEventType())) {
				vo.setTurns(vo.getTurns() + 1);
			}
			if (row.getSessionKey() != null && !row.getSessionKey().isBlank()) {
				daySessions.computeIfAbsent(day, key -> new HashSet<>()).add(row.getSessionKey());
			}
		}
		List<ActivityDailyStatVO> result = new ArrayList<>(byDay.size());
		for (Map.Entry<String, ActivityDailyStatVO> entry : byDay.entrySet()) {
			ActivityDailyStatVO vo = entry.getValue();
			vo.setSessions((long) daySessions.getOrDefault(entry.getKey(), Set.of()).size());
			result.add(vo);
		}
		return result;
	}

	/**
	 * days 实际生效值（null/非正 → 默认 {@value #STATS_DEFAULT_DAYS}；钳制
	 * {@value #STATS_MIN_DAYS} ~ {@value #STATS_MAX_DAYS}）。
	 *
	 * @param days 请求窗口天数（可 null）
	 * @return 实际生效天数
	 */
	public int resolveStatsDays(Integer days) {
		if (days == null || days <= 0) {
			return STATS_DEFAULT_DAYS;
		}
		return Math.min(Math.max(days, STATS_MIN_DAYS), STATS_MAX_DAYS);
	}

	// ============================================================
	// 清理：保留策略（M6.2 起 14 天）
	// ============================================================

	/**
	 * 物理清理指定时间之前的活动流水（ActivityCleanupJob 每日 03:30 调用；M6.2 起保留
	 * 策略 30 → 14 天：工具级数据量约为 turn 级 20 倍，14 天窗口内单 agent 百万行级上限）。
	 *
	 * @param threshold 时间阈值（早于该时间的行删除）
	 * @return 删除行数
	 */
	public int cleanupBefore(Date threshold) {
		return baseMapper.delete(Wrappers.<Activity>lambdaQuery().lt(Activity::getCreateTime, threshold));
	}

	// ============================================================
	// 内部
	// ============================================================

	/** 最近一条事件（create_time 倒序，同时间 id 倒序；无事件返回 null） */
	private Activity lastEvent(Long agentId) {
		return getOne(Wrappers.<Activity>lambdaQuery()
				.eq(Activity::getAgentId, agentId)
				.orderByDesc(Activity::getCreateTime)
				.orderByDesc(Activity::getId)
				.last("LIMIT 1"), false);
	}

	/** agent 是否门户可见（存在且未软删且 status=1；@TableLogic 自动过滤软删） */
	private boolean isAgentVisible(Long agentId) {
		if (agentId == null) {
			return false;
		}
		var agent = agentService.getById(agentId);
		return agent != null && Integer.valueOf(1).equals(agent.getStatus());
	}

	/** 字符串截断兜底（null 安全；防脏数据超长导致 SQL 500） */
	private String truncate(String value, int maxLen) {
		if (value == null) {
			return null;
		}
		return value.length() <= maxLen ? value : value.substring(0, maxLen);
	}

	/** Long 计数 → Integer（null 安全，聚合空组兜底 0） */
	private Integer intValue(Long value) {
		return value == null ? 0 : value.intValue();
	}

	/** 超限事件计数 > 0 即命中对应 long_* flag */
	private boolean hasOverLimit(Long count) {
		return count != null && count > 0;
	}

	/** dream 会话判定（记忆整合回合，sessionKey 以 DREAM_SESSION_PREFIX 开头） */
	private static boolean isDreamSession(String sessionKey) {
		return sessionKey != null && sessionKey.startsWith(DREAM_SESSION_PREFIX);
	}

	/**
	 * 确定性诊断（读时计算不落表，契约 §1.13 flags 语义）：
	 * 固定顺序 loop_suspect / long_turn / long_tool / error_prone / stalled。
	 * 可读性优化：每个 flag 同步写入人话依据（flagDetails，前端徽标 tooltip）。
	 * dream:* 会话（记忆整合回合）跳过 stalled：回合无 turn_completed 收尾，stalled 属恒误报。
	 */
	private List<String> diagnose(ActivitySessionAgg agg, String lastEventType, Map<String, LoopSuspectRow> loopRows,
			Map<String, String> errorSamples, Date now, Map<String, String> flagDetails) {
		List<String> flags = new ArrayList<>(5);
		// loop_suspect 需失败佐证：同摘要重复 ≥3 且会话 failed ≥3 才报——
		// 正常批量同类操作（样板前缀碰撞，prod 实测单会话 35 次误命中）无失败不报
		LoopSuspectRow loop = loopRows.get(agg.getSessionKey());
		if (loop != null && agg.getErrorCount() != null && agg.getErrorCount() >= ERROR_PRONE_MIN) {
			flags.add("loop_suspect");
			flagDetails.put("loop_suspect", "同参数工具调用重复 " + longValue(loop.getRepeatCount())
					+ " 次且失败 ≥" + ERROR_PRONE_MIN + "，如：" + abbreviate(loop.getSampleSummary(), 48));
		}
		if (hasOverLimit(agg.getLongTurnCount())) {
			flags.add("long_turn");
			flagDetails.put("long_turn", "最长回合 " + humanDuration(agg.getMaxTurnMs()) + "（阈值 10 分钟）");
		}
		if (hasOverLimit(agg.getLongToolCount())) {
			flags.add("long_tool");
			flagDetails.put("long_tool", "最长工具调用 " + humanDuration(agg.getMaxToolMs()) + "（阈值 2 分钟）");
		}
		if (agg.getErrorCount() != null && agg.getErrorCount() >= ERROR_PRONE_MIN) {
			flags.add("error_prone");
			String last = errorSamples.get(agg.getSessionKey());
			flagDetails.put("error_prone", agg.getErrorCount() + " 次工具失败"
					+ (last != null && !last.isBlank() ? "，最近：" + abbreviate(last, 60) : ""));
		}
		// stalled：最后事件为 turn_started/status_changed（运行中）且距今超 10 分钟无推进；
		// dream 会话（记忆整合回合）无 turn_completed 收尾，stalled 属恒误报，跳过
		if (!isDreamSession(agg.getSessionKey())
				&& (EVENT_TURN_STARTED.equals(lastEventType) || EVENT_STATUS_CHANGED.equals(lastEventType))
				&& agg.getLastEventTime() != null
				&& now.getTime() - agg.getLastEventTime().getTime() > STALLED_WINDOW_MS) {
			flags.add("stalled");
			flagDetails.put("stalled", "运行中但最近 " + (STALLED_WINDOW_MS / 60_000) + " 分钟无新事件");
		}
		return flags;
	}

	/** Long 空安全取值（null → 0） */
	private long longValue(Long value) {
		return value == null ? 0L : value;
	}

	/** 毫秒 → 人话时长（<60s → N 秒；否则 N 分钟，进位到整数分钟） */
	private String humanDuration(Long ms) {
		if (ms == null || ms <= 0) {
			return "—";
		}
		if (ms < 60_000) {
			return (ms + 999) / 1000 + " 秒";
		}
		return (ms + 59_999) / 60_000 + " 分钟";
	}

	/** 摘要缩略（null 安全，超长截断加 …，换行压空格防 tooltip 多行） */
	private String abbreviate(String text, int max) {
		if (text == null) {
			return "";
		}
		String one = text.replaceAll("\\s+", " ").trim();
		return one.length() <= max ? one : one.substring(0, max) + "…";
	}

	/** 行 → VO */
	private ActivityVO toVO(Activity row) {
		ActivityVO vo = new ActivityVO();
		vo.setId(row.getId());
		vo.setEventType(row.getEventType());
		vo.setToolName(row.getToolName());
		vo.setSummary(row.getSummary());
		vo.setDurationMs(row.getDurationMs());
		vo.setOutcome(row.getOutcome());
		vo.setSessionKey(row.getSessionKey());
		vo.setCreateTime(row.getCreateTime());
		return vo;
	}
}
