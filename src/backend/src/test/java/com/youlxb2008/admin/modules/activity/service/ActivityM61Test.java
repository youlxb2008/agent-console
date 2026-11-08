package com.youlxb2008.admin.modules.activity.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.modules.activity.dto.ActivityBatchReq;
import com.youlxb2008.admin.modules.activity.entity.Activity;
import com.youlxb2008.admin.modules.activity.vo.ActivityCurrentVO;
import com.youlxb2008.admin.modules.activity.vo.ActivityVO;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M6.1 活动流模块集成测试（service 层，映射任务书验收口径）
 * <p>
 * 覆盖：批量上报（accepted 计数 / 字段落库）/ 校验（items 空与超 50 → 400、eventType 白名单）/
 * 截断兜底（summary 512 / sessionKey 128 / toolName 64 / outcome 16）/
 * 列表（create_time 倒序、limit 钳制 ≤200、默认 50）/ agent 不可见 → 空列表（防枚举）/
 * current 聚合（running 带 toolName、turn_completed → idle + lastOutcome 回溯、
 * 10 分钟兜底强制 idle、无事件 idle 空态）/ 30 天清理（cleanupBefore 只删阈值前数据）。
 * <p>
 * scope 403 与「成功不写审计」属 Controller/ScopeAspect 层，由独立 e2e
 * （research/agc_m61_activity_e2e_test.py）覆盖，本测试为 service 层不涉及。
 * <p>
 * 使用独立内存 H2（SqlInitializer 执行 agent-domain.sql 自动建 t_activity）与
 * 临时 web.data-root，不触碰开发库/真实数据；全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcactivity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-activity" })
@Transactional
@Rollback(true)
class ActivityM61Test {

	@Resource
	private AgentService agentService;

	@Resource
	private ActivityService activityService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_act_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "活动" + tag, null, "M6.1 测试 agent", 1, 1L);
	}

	/** 构造单条事件（sessionKey 自动唯一） */
	private ActivityBatchReq.ActivityItemReq item(String eventType, String toolName, String summary,
			Integer durationMs, String outcome) {
		ActivityBatchReq.ActivityItemReq it = new ActivityBatchReq.ActivityItemReq();
		it.setEventType(eventType);
		it.setSessionKey("websocket:test-" + SEQ.incrementAndGet());
		it.setToolName(toolName);
		it.setSummary(summary);
		it.setDurationMs(durationMs);
		it.setOutcome(outcome);
		return it;
	}

	private ActivityBatchReq batch(ActivityBatchReq.ActivityItemReq... items) {
		ActivityBatchReq req = new ActivityBatchReq();
		req.setItems(List.of(items));
		return req;
	}

	/** 直插一行（绕过 report 校验，用于构造指定 createTime 的历史数据） */
	private Activity rawRow(Long agentId, String eventType, String toolName, String summary,
			Integer durationMs, String outcome, Date createTime) {
		Activity row = new Activity();
		row.setAgentId(agentId);
		row.setEventType(eventType);
		row.setSessionKey("websocket:raw-" + SEQ.incrementAndGet());
		row.setToolName(toolName);
		row.setSummary(summary);
		row.setDurationMs(durationMs);
		row.setOutcome(outcome);
		row.setCreateTime(createTime);
		activityService.save(row);
		return row;
	}

	// ------------------------------------------------------------
	// T_act_01 批量上报：accepted 计数 / 字段落库 / createTime 服务端生成
	// ------------------------------------------------------------
	@Test
	void testReport() {
		log.info("========== 开始测试 T_act_01 批量上报 ==========");
		Agent agent = newAgent("r01");
		int accepted = activityService.report(agent.getId(), batch(
				item("turn_started", null, "用户输入", null, null),
				item("tool_call", "exec", "df -h", 1200, null),
				item("turn_completed", null, "回合结束", 45000, "completed")));
		assertEquals(3, accepted, "accepted 应等于批次条数");
		List<ActivityVO> list = activityService.listByAgent(agent.getId(), 50);
		assertEquals(3, list.size(), "三条事件均应落库");
		ActivityVO toolCall = list.get(1);
		assertEquals("tool_call", toolCall.getEventType());
		assertEquals("exec", toolCall.getToolName());
		assertEquals("df -h", toolCall.getSummary());
		assertEquals(Integer.valueOf(1200), toolCall.getDurationMs());
		assertNotNull(toolCall.getCreateTime(), "createTime 应由服务端生成");
		assertNotNull(toolCall.getSessionKey(), "sessionKey 应落库");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_02 校验：items null/空 → 400、超 50 条 → 400、eventType 白名单外 → 400
	// ------------------------------------------------------------
	@Test
	void testReportValidation() {
		log.info("========== 开始测试 T_act_02 上报校验 ==========");
		Agent agent = newAgent("r02");

		ActivityBatchReq nullItems = new ActivityBatchReq();
		assertThrows(BadRequestException.class, () -> activityService.report(agent.getId(), nullItems),
				"items 缺失应 400");
		assertThrows(BadRequestException.class, () -> activityService.report(agent.getId(), batch()),
				"items 空数组应 400");

		ActivityBatchReq.ActivityItemReq[] tooMany = new ActivityBatchReq.ActivityItemReq[51];
		for (int i = 0; i < tooMany.length; i++) {
			tooMany[i] = item("turn_started", null, null, null, null);
		}
		ActivityBatchReq overBatch = new ActivityBatchReq();
		overBatch.setItems(List.of(tooMany));
		assertThrows(BadRequestException.class, () -> activityService.report(agent.getId(), overBatch),
				"51 条应 400（上限 50）");

		assertThrows(BadRequestException.class,
				() -> activityService.report(agent.getId(), batch(item("hack_event", null, null, null, null))),
				"eventType 白名单外应 400");
		assertThrows(BadRequestException.class,
				() -> activityService.report(agent.getId(), batch(item(null, null, null, null, null))),
				"eventType 缺失应 400");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_03 截断兜底：summary 512 / sessionKey 128 / toolName 64 / outcome 16
	// ------------------------------------------------------------
	@Test
	void testTruncation() {
		log.info("========== 开始测试 T_act_03 超长截断 ==========");
		Agent agent = newAgent("r03");
		activityService.report(agent.getId(), batch(
				item("tool_call", "t".repeat(100), "摘".repeat(600), null, null)));
		// sessionKey 超长走独立构造（item() 的 sessionKey 固定短值）
		ActivityBatchReq.ActivityItemReq longKey = item("turn_started", null, null, null, null);
		longKey.setSessionKey("k".repeat(300));
		longKey.setOutcome("o".repeat(30));
		activityService.report(agent.getId(), batch(longKey));

		List<ActivityVO> list = activityService.listByAgent(agent.getId(), 50);
		assertEquals(2, list.size());
		ActivityVO toolCall = list.get(1);
		assertEquals(512, toolCall.getSummary().length(), "summary 应截断到 512");
		ActivityVO started = list.get(0);
		assertEquals(128, started.getSessionKey().length(), "sessionKey 应截断到 128");
		assertEquals(64, toolCall.getToolName().length(), "toolName 应截断到 64");
		assertEquals(16, started.getOutcome().length(), "outcome 应截断到 16");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_04 列表：create_time 倒序 / limit 生效 / 默认 50 / 钳制 ≤200
	// ------------------------------------------------------------
	@Test
	void testListOrderAndLimit() {
		log.info("========== 开始测试 T_act_04 列表排序与 limit ==========");
		Agent agent = newAgent("r04");
		long now = System.currentTimeMillis();
		for (int i = 5; i >= 1; i--) {
			rawRow(agent.getId(), "turn_started", null, "事件-" + i, null, null,
					new Date(now - i * 60_000L));
		}
		// 倒序：最新在前
		List<ActivityVO> top3 = activityService.listByAgent(agent.getId(), 3);
		assertEquals(3, top3.size(), "limit=3 应只返回 3 条");
		assertEquals("事件-1", top3.get(0).getSummary(), "最新事件应在前");
		assertEquals("事件-3", top3.get(2).getSummary());
		// 默认 50：5 条全返回
		assertEquals(5, activityService.listByAgent(agent.getId(), null).size());
		// 非正数回落默认
		assertEquals(5, activityService.listByAgent(agent.getId(), 0).size());
		// 钳制 ≤200：补 200 条旧事件后，limit=1000 也只返回 200 条
		for (int i = 0; i < 200; i++) {
			rawRow(agent.getId(), "status_changed", null, "填充-" + i, null, null,
					new Date(now - 10 * 60_000L - i));
		}
		assertEquals(200, activityService.listByAgent(agent.getId(), 1000).size(),
				"limit 超上限应钳制到 200");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_05 agent 不可见：不存在 / 已停用 → 空列表（防枚举，不抛 404）
	// ------------------------------------------------------------
	@Test
	void testListInvisibleAgent() {
		log.info("========== 开始测试 T_act_05 agent 不可见容错 ==========");
		assertTrue(activityService.listByAgent(999_999_999L, 50).isEmpty(),
				"不存在的 agent 应返回空列表");
		Agent disabled = agentService.createAgent(
				"ag_act_off_" + SEQ.incrementAndGet(), "停用" + SEQ.get(), null, "已停用", 0, 1L);
		activityService.report(disabled.getId(), batch(item("turn_started", null, null, null, null)));
		assertTrue(activityService.listByAgent(disabled.getId(), 50).isEmpty(),
				"已停用（status=0）agent 应返回空列表");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_06 current：running（最后事件 tool_call 且距今 ≤10 分钟，回显 toolName）
	// ------------------------------------------------------------
	@Test
	void testCurrentRunning() {
		log.info("========== 开始测试 T_act_06 current running ==========");
		Agent agent = newAgent("r06");
		activityService.report(agent.getId(), batch(
				item("turn_started", null, "用户输入", null, null),
				item("tool_call", "exec", "df -h", 1200, null)));
		ActivityCurrentVO vo = activityService.currentByAgent(agent.getId());
		assertEquals("running", vo.getState(), "最后事件 tool_call 且新鲜 → running");
		assertEquals("exec", vo.getToolName(), "running 应回显工具名");
		assertNotNull(vo.getSinceMs());
		assertTrue(vo.getSinceMs() <= 60_000L, "sinceMs 应为刚发生（≤60s）");
		assertEquals("df -h", vo.getLastSummary());
		assertNull(vo.getLastOutcome(), "running 态不回填 lastOutcome");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_07 current：turn_completed → idle + lastOutcome 回溯
	// ------------------------------------------------------------
	@Test
	void testCurrentIdleAfterComplete() {
		log.info("========== 开始测试 T_act_07 current idle（完成后） ==========");
		Agent agent = newAgent("r07");
		activityService.report(agent.getId(), batch(
				item("tool_call", "exec", "df -h", 1200, null),
				item("turn_completed", null, "回合结束", 45000, "completed")));
		ActivityCurrentVO vo = activityService.currentByAgent(agent.getId());
		assertEquals("idle", vo.getState(), "最后事件 turn_completed → idle");
		assertNull(vo.getToolName(), "idle 不带工具名");
		assertEquals("completed", vo.getLastOutcome(), "idle 应回溯最近一次完成结果");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_08 current：10 分钟兜底——最后事件 tool_call 但距今 11 分钟 → 强制 idle
	// ------------------------------------------------------------
	@Test
	void testCurrentIdleTimeout() {
		log.info("========== 开始测试 T_act_08 current 10 分钟兜底 ==========");
		Agent agent = newAgent("r08");
		long now = System.currentTimeMillis();
		rawRow(agent.getId(), "turn_completed", null, "更早回合", 1000, "completed",
				new Date(now - 20 * 60_000L));
		rawRow(agent.getId(), "tool_call", "exec", "失联前的最后动作", 1200, null,
				new Date(now - 11 * 60_000L));
		ActivityCurrentVO vo = activityService.currentByAgent(agent.getId());
		assertEquals("idle", vo.getState(), "最后事件距今 11 分钟 > 10 分钟窗口 → 强制 idle");
		assertTrue(vo.getSinceMs() >= 10 * 60_000L, "sinceMs 应 ≥10 分钟");
		assertEquals("completed", vo.getLastOutcome(), "idle 应回溯更早的 turn_completed 结果");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_09 current：无事件 → idle 空态（全字段 null，不抛 404）
	// ------------------------------------------------------------
	@Test
	void testCurrentNoEvents() {
		log.info("========== 开始测试 T_act_09 current 空态 ==========");
		Agent agent = newAgent("r09");
		ActivityCurrentVO vo = activityService.currentByAgent(agent.getId());
		assertEquals("idle", vo.getState());
		assertNull(vo.getSinceMs());
		assertNull(vo.getLastOutcome());
		assertNull(vo.getLastSummary());
		assertNull(vo.getLastEventTime());
		// 不存在的 agent 同样 idle 空态（防枚举）
		assertEquals("idle", activityService.currentByAgent(999_999_999L).getState());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_act_10 30 天清理：cleanupBefore 只删阈值前数据，新数据保留
	// ------------------------------------------------------------
	@Test
	void testCleanup() {
		log.info("========== 开始测试 T_act_10 30 天清理 ==========");
		Agent agent = newAgent("r10");
		long now = System.currentTimeMillis();
		rawRow(agent.getId(), "turn_started", null, "31 天前", null, null,
				new Date(now - 31L * 24 * 3600 * 1000));
		rawRow(agent.getId(), "turn_started", null, "29 天前", null, null,
				new Date(now - 29L * 24 * 3600 * 1000));
		rawRow(agent.getId(), "tool_call", "exec", "刚刚", 100, null, new Date(now));
		int deleted = activityService.cleanupBefore(new Date(now - 30L * 24 * 3600 * 1000));
		assertEquals(1, deleted, "只应删除 31 天前那 1 条");
		List<ActivityVO> rest = activityService.listByAgent(agent.getId(), 50);
		assertEquals(2, rest.size(), "29 天前与刚刚的应保留");
		assertEquals("刚刚", rest.get(0).getSummary());
		log.info("========== 测试执行完成 ==========");
	}
}
