package com.youlxb2008.admin.modules.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.activity.entity.Activity;
import com.youlxb2008.admin.modules.activity.vo.ActivitySessionVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityVO;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M6.2 工具级活动流扩展测试（service 层，映射任务书验收口径）
 * <p>
 * 覆盖：话题聚合（分组计数/totalActiveMs/lastEventTime/话题摘要=最早 turn_started summary/
 * 最近活跃倒序）/ 五条诊断规则正确性与边界（loop_suspect 刚好 3 次 vs 2 次、long_turn/long_tool
 * 阈值内 vs 外、error_prone 3 次 vs 2 次、stalled 运行中超窗 vs 已完成 vs 工具收尾）/
 * flags 固定顺序 / sessionKey 精确过滤 / agent 不可见 → 空列表（防枚举）/ limit 钳制 /
 * dream:* 会话（记忆整合回合）固定摘要 + stalled 豁免（非 dream 会话行为不变）。
 * <p>
 * 使用独立内存 H2（SqlInitializer 执行 agent-domain.sql 自动建 t_activity），
 * 全部 @Transactional 回滚，不触碰开发库/真实数据。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcactivity62;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-activity62" })
@Transactional
@Rollback(true)
class ActivityM62Test {

	@Resource
	private AgentService agentService;

	@Resource
	private ActivityService activityService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_act62_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "活动62" + tag, null, "M6.2 测试 agent", 1, 1L);
	}

	/** 直插一行（指定 sessionKey 与 createTime，构造聚合/诊断场景） */
	private Activity row(Long agentId, String sessionKey, String eventType, String toolName, String summary,
			Integer durationMs, String outcome, Date createTime) {
		Activity r = new Activity();
		r.setAgentId(agentId);
		r.setEventType(eventType);
		r.setSessionKey(sessionKey);
		r.setToolName(toolName);
		r.setSummary(summary);
		r.setDurationMs(durationMs);
		r.setOutcome(outcome);
		r.setCreateTime(createTime);
		activityService.save(r);
		return r;
	}

	// ------------------------------------------------------------
	// T_m62_01 话题聚合：分组计数 / totalActiveMs / 话题摘要 / 最近活跃倒序
	// ------------------------------------------------------------
	@Test
	void testSessionsAggregation() {
		log.info("========== 开始测试 T_m62_01 话题聚合 ==========");
		Agent agent = newAgent("a01");
		long now = System.currentTimeMillis();
		// 会话 S1（较早）：2 turn + 2 tool（1 failed）→ turnCount=2 toolCallCount=2 errorCount=1
		row(agent.getId(), "websocket:s1", "turn_started", null, "话题一", null, null,
				new Date(now - 10 * 60_000L));
		row(agent.getId(), "websocket:s1", "tool_call", "exec", "df -h", 1200, null,
				new Date(now - 9 * 60_000L));
		row(agent.getId(), "websocket:s1", "tool_call", "exec", "bad cmd", 300, "failed",
				new Date(now - 8 * 60_000L));
		row(agent.getId(), "websocket:s1", "turn_started", null, "话题一回合二", null, null,
				new Date(now - 7 * 60_000L - 30_000L));
		row(agent.getId(), "websocket:s1", "turn_completed", null, "回合结束", 45_000, "completed",
				new Date(now - 7 * 60_000L));
		// 会话 S2（最近活跃）：1 turn + 1 tool
		row(agent.getId(), "websocket:s2", "turn_started", null, "话题二", null, null,
				new Date(now - 3 * 60_000L));
		row(agent.getId(), "websocket:s2", "tool_call", "search", "query", 800, null,
				new Date(now - 2 * 60_000L));

		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(2, sessions.size(), "两个会话各聚合一行");
		// 最近活跃倒序：S2 在前
		assertEquals("websocket:s2", sessions.get(0).getSessionKey());
		assertEquals("websocket:s1", sessions.get(1).getSessionKey());
		// S2 统计
		ActivitySessionVO s2 = sessions.get(0);
		assertEquals("话题二", s2.getTopicSummary(), "话题摘要 = 最早 turn_started 的 summary");
		assertEquals(1, s2.getTurnCount());
		assertEquals(1, s2.getToolCallCount());
		assertEquals(0, s2.getErrorCount());
		assertEquals(Long.valueOf(800L), s2.getTotalActiveMs(), "totalActiveMs = 组内 durationMs 合计");
		// S1 统计
		ActivitySessionVO s1 = sessions.get(1);
		assertEquals("话题一", s1.getTopicSummary());
		assertEquals(2, s1.getTurnCount());
		assertEquals(2, s1.getToolCallCount());
		assertEquals(1, s1.getErrorCount());
		assertEquals(Long.valueOf(1200 + 300 + 45_000L), s1.getTotalActiveMs());
		assertTrue(s1.getLastEventTime().after(s2.getLastEventTime()) == false
				|| s1.getLastEventTime().before(s2.getLastEventTime()), "lastEventTime 非空");
		assertTrue(s1.getFlags().isEmpty(), "无异常会话 flags 为空列表");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_02 loop_suspect：同会话 (tool_name, summary 全文) start 事件重复 ≥3 且会话
	// failed ≥3（失败佐证）→ flag；重复但无失败 → 不命中（正常批量查询回归）；
	// 2 次 → 无；end 事件（outcome 非空，v2 存量 summary="ok"）→ 不命中（误报回归）；
	// 同前缀不同全文 → 不命中（样板碰撞回归：prod 实测 source secrets.env 样板开头
	// 导致不同命令同前 64 字符被误判循环）
	// ------------------------------------------------------------
	@Test
	void testLoopSuspect() {
		log.info("========== 开始测试 T_m62_02 loop_suspect ==========");
		Agent agent = newAgent("a02");
		long now = System.currentTimeMillis();
		// 会话 LOOP：同工具同参数 ×3（start 语义 outcome=null）+ failed ×3（失败佐证）→ 命中
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:loop", "tool_call", "exec", "retry same cmd", 100, null,
					new Date(now - 5 * 60_000L + i));
			row(agent.getId(), "websocket:loop", "tool_call", "exec", "retry same cmd", 100, "failed",
					new Date(now - 5 * 60_000L + i + 50));
		}
		// 会话 LOOPOK：同摘要 ×3 但无失败 → 不命中
		// （回归：正常批量同类操作无失败不报——插件摘要截断 160 致样板碰撞，纯重复判定误报）
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:loopok", "tool_call", "exec", "batch query same shape", 100, null,
					new Date(now - 7 * 60_000L + i));
		}
		// 会话 OK：同工具同参数 ×2 → 不命中（边界：刚好 2 次）
		for (int i = 0; i < 2; i++) {
			row(agent.getId(), "websocket:ok", "tool_call", "exec", "retry same cmd", 100, null,
					new Date(now - 4 * 60_000L + i));
		}
		// 会话 DIFF：×3 但 summary 前 64 字符不同 → 不命中
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:diff", "tool_call", "exec", "different arg " + i, 100, null,
					new Date(now - 3 * 60_000L + i));
		}
		// 会话 ENDOK：end 事件 ×3（outcome=completed，v2 存量 summary="ok"）→ 不命中
		// （回归：v2 插件 end summary 恒 "ok"，曾把所有正常会话误判 loop_suspect）
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:endok", "tool_call", "exec", "ok", 100, "completed",
					new Date(now - 2 * 60_000L + i));
		}
		// 会话 PREFIX：前 64 字符相同但全文不同 ×3 → 不命中
		// （回归：前缀口径样板碰撞误报——exec 命令惯用样板开头，不同命令同前缀曾被误判循环）
		String boilerplate = "B64=$(echo -n \"$PW\" | base64 -w0)&&cd /home/demo/workspace && timeout 100 python3 x";
		assertTrue(boilerplate.length() >= 64, "样板前缀需覆盖比对窗口");
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:prefix", "tool_call", "exec", boilerplate + " query-" + i, 100, null,
					new Date(now - 6 * 60_000L + i));
		}
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(6, sessions.size());
		for (ActivitySessionVO s : sessions) {
			if ("websocket:loop".equals(s.getSessionKey())) {
				assertTrue(s.getFlags().contains("loop_suspect"), "同参数 ×3 且失败 ×3 应命中 loop_suspect");
				assertTrue(s.getFlagDetails() != null
						&& s.getFlagDetails().get("loop_suspect") != null
						&& s.getFlagDetails().get("loop_suspect").contains("3 次"),
						"loop_suspect 应附人话依据（重复次数）");
				assertTrue(s.getFlagDetails().get("loop_suspect").contains("失败"),
						"loop_suspect 人话依据应含失败佐证");
			} else {
				assertTrue(!s.getFlags().contains("loop_suspect"),
						s.getSessionKey() + " 不应命中 loop_suspect");
			}
		}
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_03 long_turn / long_tool：阈值外命中、阈值内不命中（> 超限语义）
	// ------------------------------------------------------------
	@Test
	void testLongTurnAndLongTool() {
		log.info("========== 开始测试 T_m62_03 long_turn/long_tool ==========");
		Agent agent = newAgent("a03");
		long now = System.currentTimeMillis();
		// 会话 OVER：turn 600_001ms（>10min）+ tool 120_001ms（>2min）→ 双命中
		row(agent.getId(), "websocket:over", "turn_started", null, "超长回合", null, null,
				new Date(now - 30 * 60_000L));
		row(agent.getId(), "websocket:over", "tool_call", "exec", "slow tool", 120_001, null,
				new Date(now - 20 * 60_000L));
		row(agent.getId(), "websocket:over", "turn_completed", null, "回合结束", 600_001, "completed",
				new Date(now - 10 * 60_000L));
		// 会话 EDGE：turn 600_000 + tool 120_000（刚好等于阈值，不超）→ 不命中
		row(agent.getId(), "websocket:edge", "turn_started", null, "边界回合", null, null,
				new Date(now - 30 * 60_000L));
		row(agent.getId(), "websocket:edge", "tool_call", "exec", "edge tool", 120_000, null,
				new Date(now - 20 * 60_000L));
		row(agent.getId(), "websocket:edge", "turn_completed", null, "回合结束", 600_000, "completed",
				new Date(now - 10 * 60_000L));
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(2, sessions.size());
		ActivitySessionVO over = sessions.stream()
				.filter(s -> "websocket:over".equals(s.getSessionKey())).findFirst().orElseThrow();
		assertTrue(over.getFlags().contains("long_turn"), "turn 600_001ms > 10min 应命中 long_turn");
		assertTrue(over.getFlags().contains("long_tool"), "tool 120_001ms > 2min 应命中 long_tool");
		ActivitySessionVO edge = sessions.stream()
				.filter(s -> "websocket:edge".equals(s.getSessionKey())).findFirst().orElseThrow();
		assertTrue(!edge.getFlags().contains("long_turn"), "turn 600_000ms 恰等于阈值不应命中");
		assertTrue(!edge.getFlags().contains("long_tool"), "tool 120_000ms 恰等于阈值不应命中");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_04 error_prone：failed tool_call ≥3 → flag；2 次 → 无
	// ------------------------------------------------------------
	@Test
	void testErrorProne() {
		log.info("========== 开始测试 T_m62_04 error_prone ==========");
		Agent agent = newAgent("a04");
		long now = System.currentTimeMillis();
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:err3", "tool_call", "exec", "cmd " + i, 100, "failed",
					new Date(now - 5 * 60_000L + i));
		}
		for (int i = 0; i < 2; i++) {
			row(agent.getId(), "websocket:err2", "tool_call", "exec", "cmd " + i, 100, "failed",
					new Date(now - 4 * 60_000L + i));
		}
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(2, sessions.size());
		for (ActivitySessionVO s : sessions) {
			if ("websocket:err3".equals(s.getSessionKey())) {
				assertTrue(s.getFlags().contains("error_prone"), "failed ×3 应命中 error_prone");
				assertEquals(3, s.getErrorCount());
			} else {
				assertTrue(!s.getFlags().contains("error_prone"), "failed ×2 不应命中");
				assertEquals(2, s.getErrorCount());
			}
		}
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_05 stalled：最后事件 turn_started/status_changed 且距今 >5min → flag；
	// turn_completed / tool_call 收尾 → 不命中
	// ------------------------------------------------------------
	@Test
	void testStalled() {
		log.info("========== 开始测试 T_m62_05 stalled ==========");
		Agent agent = newAgent("a05");
		long now = System.currentTimeMillis();
		// 会话 STUCK：最后事件 turn_started/status_changed 且距今 11 分钟（>10min 窗）→ 命中
		row(agent.getId(), "websocket:stuck", "turn_started", null, "卡住的话题", null, null,
				new Date(now - 12 * 60_000L));
		row(agent.getId(), "websocket:stuck", "tool_call", "exec", "step", 100, null,
				new Date(now - 11 * 60_000L - 1000));
		row(agent.getId(), "websocket:stuck", "status_changed", null, "运行中", null, null,
				new Date(now - 11 * 60_000L));
		// 会话 DONE：最后事件 turn_completed（虽旧）→ 不命中
		row(agent.getId(), "websocket:done", "turn_started", null, "完成的话题", null, null,
				new Date(now - 30 * 60_000L));
		row(agent.getId(), "websocket:done", "turn_completed", null, "回合结束", 1000, "completed",
				new Date(now - 20 * 60_000L));
		// 会话 TOOLTAIL：最后事件 tool_call（不在 stalled 事件类型内）→ 不命中
		row(agent.getId(), "websocket:tooltail", "turn_started", null, "工具收尾", null, null,
				new Date(now - 30 * 60_000L));
		row(agent.getId(), "websocket:tooltail", "tool_call", "exec", "last action", 100, null,
				new Date(now - 20 * 60_000L));
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(3, sessions.size());
		for (ActivitySessionVO s : sessions) {
			if ("websocket:stuck".equals(s.getSessionKey())) {
				assertTrue(s.getFlags().contains("stalled"), "运行中且 11 分钟无推进应命中 stalled");
				assertTrue(s.getFlagDetails() != null
						&& s.getFlagDetails().get("stalled") != null,
						"stalled 应附人话依据");
			} else {
				assertTrue(!s.getFlags().contains("stalled"),
						s.getSessionKey() + " 不应命中 stalled");
			}
		}
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_06 flags 固定顺序：全命中会话 = [loop_suspect, long_turn, long_tool, error_prone, stalled]
	// ------------------------------------------------------------
	@Test
	void testFlagsOrder() {
		log.info("========== 开始测试 T_m62_06 flags 固定顺序 ==========");
		Agent agent = newAgent("a06");
		long now = System.currentTimeMillis();
		// 全 flag 会话：loop ×3（start 语义）+ long tool + long turn + failed ×3 + 最后事件 turn_started（11 分钟前，时间最大）
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:all", "tool_call", "exec", "same cmd", 100, null,
					new Date(now - 14 * 60_000L + i));
		}
		for (int i = 0; i < 3; i++) {
			row(agent.getId(), "websocket:all", "tool_call", "exec", "boom " + i, 120_001, "failed",
					new Date(now - 13 * 60_000L + i));
		}
		row(agent.getId(), "websocket:all", "turn_completed", null, "回合结束", 600_001, "completed",
				new Date(now - 12 * 60_000L));
		row(agent.getId(), "websocket:all", "turn_started", null, "全 flag 话题", null, null,
				new Date(now - 11 * 60_000L));
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(1, sessions.size());
		assertEquals(List.of("loop_suspect", "long_turn", "long_tool", "error_prone", "stalled"),
				sessions.get(0).getFlags(), "flags 应按契约固定顺序输出");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_07 sessionKey 精确过滤（M6.2 列表接口增强）
	// ------------------------------------------------------------
	@Test
	void testListSessionKeyFilter() {
		log.info("========== 开始测试 T_m62_07 sessionKey 过滤 ==========");
		Agent agent = newAgent("a07");
		long now = System.currentTimeMillis();
		row(agent.getId(), "websocket:fa", "turn_started", null, "会话A", null, null,
				new Date(now - 5 * 60_000L));
		row(agent.getId(), "websocket:fb", "turn_started", null, "会话B", null, null,
				new Date(now - 4 * 60_000L));
		row(agent.getId(), "websocket:fa", "tool_call", "exec", "A 的工具", 100, null,
				new Date(now - 3 * 60_000L));
		row(agent.getId(), "websocket:fb", "tool_call", "exec", "B 的工具", 100, null,
				new Date(now - 2 * 60_000L));
		// 过滤会话 A：只返回 A 的 2 条（倒序）
		List<ActivityVO> filtered = activityService.listByAgent(agent.getId(), 200, "websocket:fa");
		assertEquals(2, filtered.size(), "sessionKey 过滤应只返回该会话事件");
		assertEquals("A 的工具", filtered.get(0).getSummary());
		assertEquals("会话A", filtered.get(1).getSummary());
		// 不过滤（null/空白）：全部 4 条
		assertEquals(4, activityService.listByAgent(agent.getId(), 200, null).size());
		assertEquals(4, activityService.listByAgent(agent.getId(), 200, "  ").size(), "空白视为不过滤");
		// 无匹配会话 → 空列表
		assertTrue(activityService.listByAgent(agent.getId(), 200, "websocket:none").isEmpty());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_08 防枚举与 limit：不存在/停用 agent → 空列表；limit 钳制 ≤50、默认 20
	// ------------------------------------------------------------
	@Test
	void testSessionsInvisibleAgentAndLimit() {
		log.info("========== 开始测试 T_m62_08 防枚举与 limit ==========");
		assertTrue(activityService.listSessions(999_999_999L, 20).isEmpty(), "不存在的 agent 应空列表");
		Agent disabled = agentService.createAgent(
				"ag_act62_off_" + SEQ.incrementAndGet(), "停用62" + SEQ.get(), null, "已停用", 0, 1L);
		row(disabled.getId(), "websocket:x", "turn_started", null, "停用会话", null, null, new Date());
		assertTrue(activityService.listSessions(disabled.getId(), 20).isEmpty(), "停用 agent 应空列表");

		Agent agent = newAgent("a08");
		long now = System.currentTimeMillis();
		for (int i = 0; i < 25; i++) {
			row(agent.getId(), "websocket:s" + i, "turn_started", null, "话题" + i, null, null,
					new Date(now - i * 1000L));
		}
		assertEquals(20, activityService.listSessions(agent.getId(), null).size(), "默认 limit=20");
		assertEquals(1, activityService.listSessions(agent.getId(), 1).size(), "limit=1 生效");
		assertEquals(20, activityService.listSessions(agent.getId(), 0).size(), "非正数回落默认 20");
		assertEquals(25, activityService.listSessions(agent.getId(), 1000).size(),
				"超上限钳制 ≤50（25 会话 < 50 应全量返回）");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_09 dream 会话可读性：话题摘要固定「记忆整合（dream）」（覆盖 topics 查找结果）
	// + 诊断跳过 stalled（dream 回合不发 turn_completed，stalled 属恒误报）
	// ------------------------------------------------------------
	@Test
	void testDreamSessionFixedLabelAndNoStalled() {
		log.info("========== 开始测试 T_m62_09 dream 会话固定摘要与 stalled 豁免 ==========");
		Agent agent = newAgent("a09");
		long now = System.currentTimeMillis();
		// 会话 DA：turn_started 有非空 summary + status_changed 尾、距今 11 分钟（超窗）——
		// dream 回合不发 turn_completed；若无豁免将命中 stalled，摘要也会取 turn_started 文本
		row(agent.getId(), "dream:2026091520", "turn_started", null, "梦境整合文本", null, null,
				new Date(now - 12 * 60_000L));
		row(agent.getId(), "dream:2026091520", "tool_call", "exec", "consolidate memory", 100, null,
				new Date(now - 11 * 60_000L - 1000));
		row(agent.getId(), "dream:2026091520", "status_changed", null, null, null, null,
				new Date(now - 11 * 60_000L));
		// 会话 DB：summary 全空 + turn_started 尾、距今 30 分钟（超窗）
		row(agent.getId(), "dream:2026091518", "turn_started", null, null, null, null,
				new Date(now - 30 * 60_000L));
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(2, sessions.size());
		for (ActivitySessionVO s : sessions) {
			assertEquals("记忆整合（dream）", s.getTopicSummary(),
					s.getSessionKey() + " 话题摘要应固定（覆盖 topics 查找结果，非空 summary 也不例外）");
			assertTrue(!s.getFlags().contains("stalled"),
					s.getSessionKey() + " dream 回合无 turn_completed，stalled 应豁免");
		}
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_m62_10 非 dream 会话行为不变：同构超窗无完成事件会话仍命中 stalled、
	// 摘要仍取最早 turn_started summary（dream 豁免不影响普通会话口径）
	// ------------------------------------------------------------
	@Test
	void testNonDreamSessionUnchanged() {
		log.info("========== 开始测试 T_m62_10 非 dream 会话行为不变 ==========");
		Agent agent = newAgent("a10");
		long now = System.currentTimeMillis();
		// 与 dream 场景同构（status_changed 尾 + 超窗 + 无 turn_completed），仅前缀不同 → 仍命中 stalled
		row(agent.getId(), "websocket:plain", "turn_started", null, "普通话题", null, null,
				new Date(now - 12 * 60_000L));
		row(agent.getId(), "websocket:plain", "tool_call", "exec", "normal work", 100, null,
				new Date(now - 11 * 60_000L - 1000));
		row(agent.getId(), "websocket:plain", "status_changed", null, null, null, null,
				new Date(now - 11 * 60_000L));
		// 已完成会话：turn_completed 收尾（虽旧）→ 无 stalled，摘要 = turn_started summary
		row(agent.getId(), "websocket:done", "turn_started", null, "已完成话题", null, null,
				new Date(now - 30 * 60_000L));
		row(agent.getId(), "websocket:done", "turn_completed", null, "回合结束", 1000, "completed",
				new Date(now - 20 * 60_000L));
		List<ActivitySessionVO> sessions = activityService.listSessions(agent.getId(), 20);
		assertEquals(2, sessions.size());
		for (ActivitySessionVO s : sessions) {
			if ("websocket:plain".equals(s.getSessionKey())) {
				assertTrue(s.getFlags().contains("stalled"),
						"非 dream 超窗无完成事件仍应命中 stalled（行为不变）");
				assertEquals("普通话题", s.getTopicSummary(), "非 dream 摘要仍取最早 turn_started summary");
			} else {
				assertTrue(!s.getFlags().contains("stalled"), "已完成会话不应命中 stalled");
				assertEquals("已完成话题", s.getTopicSummary());
			}
		}
		log.info("========== 测试执行完成 ==========");
	}
}
