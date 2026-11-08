package com.youlxb2008.admin.modules.bus.fuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusTopicWatch;
import com.youlxb2008.admin.modules.bus.fuse.BusFuseService;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.mapper.BusTopicWatchMapper;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;

import jakarta.annotation.Resource;

/**
 * 总线超时保险丝扫描判据集成测试（bus-watchdog-fuse ①，任务书验收 1~5 项的判据层）
 * <p>
 * 独立内存 H2（MODE=MySQL），DB 全部 @Transactional 回滚；扫描器不后台跑
 * （bus.fuse.scan-interval-ms=-1），用例直接调 {@link BusFuseService#scanOnce()}，
 * 时限用 create_time 回拨控制（等价真实等待，不 sleep）。
 * <p>
 * 覆盖映射（任务书验收 §）：
 * 消息级时限超时 → [fuse] 告警落 BUS_ALERT_SESSION（①/验收1）；
 * 回音销案不告警（验收2）；打回重计再告警（验收3）；默认时限 env 生效 + 消息级覆盖优先（验收4）；
 * 同一等待段只告警一次（验收5）；自投线程（交付回投 deliver-*）不判超时（回投不误报）；
 * [fuse] topic 排除（防自触发）；7 天窗口外不扫；系统消息收尾销案；
 * warn-only（历史 alertCount=0）在 env 配置后同段升级补投。
 */
@SpringBootTest(classes = { WebApplication.class, BusFuseScanTest.FakeRedisConfig.class }, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:busfuse;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-bus-fuse",
		"bus.dispatch.enabled=false",
		"bus.fuse.scan-interval-ms=-1",
		"bus.fuse.default.minutes=1",
		"bus.alert.session=websocket:fuse-main-window" })
@Transactional
@Rollback(true)
class BusFuseScanTest {

	/** 发起人（管理员 agent alpha） */
	private static final long INITIATOR = 1L;
	/** 目标 worker */
	private static final long WORKER = 2L;
	/** 3 分钟前（> 默认/消息级 1 分钟时限，必超时） */
	private static final long EXPIRED_MS = 3L * 60L * 1000L;

	@Autowired
	private AgentMessageMapper messageMapper;

	@Autowired
	private BusTopicWatchMapper watchMapper;

	@Autowired
	private BusFuseService fuseService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void clean() {
		messageMapper.delete(Wrappers.<AgentMessage>lambdaQuery()
				.like(AgentMessage::getTopic, "fuse"));
		watchMapper.delete(Wrappers.<BusTopicWatch>lambdaQuery()
				.like(BusTopicWatch::getTopic, "fuse"));
	}

	// ==================== 工具 ====================

	private AgentMessage insertMessage(long from, long to, String topic, Integer expectMinutes,
			long ageMillis) {
		AgentMessage row = new AgentMessage();
		row.setMessageId(UUID.randomUUID().toString());
		row.setFromAgentId(from);
		row.setToAgentId(to);
		row.setMsgType("notify");
		row.setTopic(topic);
		row.setTitle("t-" + topic);
		row.setStatus(BusMessageService.STATUS_DELIVERED);
		row.setRetryCount(0);
		row.setExpectReplyMinutes(expectMinutes);
		Date at = new Date(System.currentTimeMillis() - ageMillis);
		row.setCreateTime(at);
		row.setUpdateTime(at);
		messageMapper.insert(row);
		return row;
	}

	private AgentMessage lastAlert(String topic) {
		return messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getTopic, BusFuseService.ALERT_TOPIC_PREFIX + topic)
				.orderByDesc(AgentMessage::getId)
				.last("LIMIT 1"));
	}

	private long countAlerts(String topic) {
		return messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getTopic, BusFuseService.ALERT_TOPIC_PREFIX + topic));
	}

	private BusTopicWatch watch(String topic) {
		return watchMapper.selectOne(Wrappers.<BusTopicWatch>lambdaQuery()
				.eq(BusTopicWatch::getTopic, topic));
	}

	// ==================== 用例 ====================

	@Test
	@DisplayName("T_fuse_01 消息级时限超时 → [fuse] 告警落 BUS_ALERT_SESSION（验收1判据）")
	void t01_expiredAlertsIntoConfiguredSession() throws Exception {
		String topic = "fuse-demo-a";
		AgentMessage last = insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		AgentMessage alert = lastAlert(topic);
		assertNotNull(alert, "超时应生成告警消息");
		assertEquals(0L, alert.getFromAgentId(), "告警应为系统消息（from=0）");
		assertEquals(INITIATOR, alert.getToAgentId(), "告警应收件人=发起人");
		assertEquals("alert", alert.getMsgType(), "msg_type 复用 alert");
		assertEquals(BusFuseService.ALERT_TOPIC_PREFIX + topic, alert.getTopic());
		assertEquals("websocket:fuse-main-window", alert.getSessionKey(),
				"告警落点=BUS_ALERT_SESSION（不新开会话，硬需求）");
		assertEquals(BusMessageService.STATUS_PENDING, alert.getStatus(), "告警进正常投递链");
		JsonNode payload = objectMapper.readTree(alert.getPayload());
		assertEquals(topic, payload.path("sourceTopic").asText(), "payload 应注明来源 topic");
		assertEquals(1, payload.path("expectReplyMinutes").asInt(), "payload 应注明等待时限");
		BusTopicWatch row = watch(topic);
		assertNotNull(row, "应登记 watch 等待段");
		assertEquals(last.getId(), row.getAlertedMessageId(), "watch 锚点=末条消息 id");
		assertEquals(1, row.getAlertCount(), "已投递告警 alertCount=1");
		assertNotNull(row.getLastAlertAt());
	}

	@Test
	@DisplayName("T_fuse_02 同 topic 有回音 → 不告警、watch 销案（验收2）")
	void t02_replyResolvesNoAlert() {
		String topic = "fuse-demo-b";
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		// 回音（to→from 反向，时间更新）
		insertMessage(WORKER, INITIATOR, topic, null, 1_000L);
		fuseService.scanOnce();
		assertEquals(0, countAlerts(topic), "已有回音不应告警");
		assertNull(watch(topic), "回音应销案（watch 删行）");
	}

	@Test
	@DisplayName("T_fuse_03 打回重计：回音后发起人再发，超时后再次告警（验收3）")
	void t03_rejectThenResendAlertsAgain() {
		String topic = "fuse-demo-c";
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic), "第一段应告警一次");
		// 回音（worker 回报）→ 新等待段之前的销案
		insertMessage(WORKER, INITIATOR, topic, null, 1_000L);
		// 打回（发起人再发，同样超时）
		AgentMessage resend = insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(2, countAlerts(topic), "新等待段应再次告警");
		BusTopicWatch row = watch(topic);
		assertNotNull(row);
		assertEquals(resend.getId(), row.getAlertedMessageId(), "锚点应前移到新段末条");
		assertEquals(2, row.getAlertCount(), "累计告警次数应为 2");
	}

	@Test
	@DisplayName("T_fuse_04 默认时限 env 生效；消息级覆盖优先（验收4）")
	void t04_defaultMinutesAndOverride() {
		// 无 expectReplyMinutes → 默认 1 分钟（bus.fuse.default.minutes=1），3 分钟前已超时
		String byDefault = "fuse-demo-d1";
		insertMessage(INITIATOR, WORKER, byDefault, null, EXPIRED_MS);
		// 消息级 1440 分钟覆盖默认 → 未超时
		String overridden = "fuse-demo-d2";
		insertMessage(INITIATOR, WORKER, overridden, BusMessageService.EXPECT_REPLY_MAX_MINUTES, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(byDefault), "无覆盖者走默认时限应告警");
		assertEquals(0, countAlerts(overridden), "消息级覆盖（1440）未到时不应告警");
		assertNull(watch(overridden), "未超时不应登记 watch");
	}

	@Test
	@DisplayName("T_fuse_05 同一等待段只告警一次（多轮扫描不重复，验收5）")
	void t05_sameSegmentAlertsOnce() {
		String topic = "fuse-demo-e";
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		fuseService.scanOnce();
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic), "同一等待段多轮扫描只告警一次");
		assertEquals(1, watch(topic).getAlertCount());
	}

	@Test
	@DisplayName("T_fuse_06 自投线程（deliver-* 回投，from=to）不判超时")
	void t06_selfThreadNoAlert() {
		// 交付回投：to=自己（from=to=1），消息已交付即完成，无回音期待
		insertMessage(INITIATOR, INITIATOR, "fuse-deliver-x", null, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(0, countAlerts("fuse-deliver-x"), "自投线程不应触发保险丝");
		assertNull(watch("fuse-deliver-x"));
	}

	@Test
	@DisplayName("T_fuse_07 [fuse] 前缀 topic 排除在判定外（防自触发）")
	void t07_fuseTopicExcluded() {
		insertMessage(INITIATOR, WORKER, BusFuseService.ALERT_TOPIC_PREFIX + "demo", 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(0, countAlerts(BusFuseService.ALERT_TOPIC_PREFIX + "demo"),
				"[fuse] 前缀 topic 不参与超时判定");
		assertNull(watch(BusFuseService.ALERT_TOPIC_PREFIX + "demo"));
	}

	@Test
	@DisplayName("T_fuse_08 近 7 天窗口外 topic 不扫（任务书①：防全表扫与陈年误报）")
	void t08_oldTopicOutsideWindow() {
		String topic = "fuse-ancient";
		insertMessage(INITIATOR, WORKER, topic, 1, 8L * 24L * 3600L * 1000L);
		fuseService.scanOnce();
		assertEquals(0, countAlerts(topic), "7 天窗口外的陈年 topic 不判定");
		assertNull(watch(topic));
	}

	@Test
	@DisplayName("T_fuse_09 系统消息（from=0）收尾 → 销案且不再告警")
	void t09_systemMessageEndsSegment() {
		String topic = "fuse-demo-g";
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic));
		assertNotNull(watch(topic));
		// 系统消息成为新末条 → 等待段结束
		insertMessage(0L, INITIATOR, topic, null, 1_000L);
		fuseService.scanOnce();
		assertNull(watch(topic), "系统消息收尾应销案");
		assertEquals(1, countAlerts(topic), "收尾后不应再告警");
	}

	@Test
	@DisplayName("T_fuse_10 发起人自续命重置计时（新末条=新等待段）")
	void t10_selfExtendResetsSegment() {
		String topic = "fuse-demo-h";
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		// 发起人 10 秒前补充说明（仍在等待态，但计时从新消息起算 → 未超时）
		insertMessage(INITIATOR, WORKER, topic, null, 10_000L);
		fuseService.scanOnce();
		assertEquals(0, countAlerts(topic), "自续命后计时重置，不应告警");
	}

	@Test
	@DisplayName("T_fuse_11 warn-only 等待段（alertCount=0）在 env 已配置后升级补投")
	void t11_warnOnlyUpgradesAfterEnvConfigured() {
		String topic = "fuse-demo-i";
		AgentMessage last = insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		// 手工预置 warn-only watch（模拟 BUS_ALERT_SESSION 未配置时代的发现记录）
		BusTopicWatch warned = new BusTopicWatch();
		warned.setTopic(topic);
		warned.setAlertedMessageId(last.getId());
		warned.setAlertCount(0);
		warned.setCreateTime(new Date());
		warned.setUpdateTime(new Date());
		watchMapper.insert(warned);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic), "同段未投递过（alertCount=0）且 env 已配置 → 升级补投");
		BusTopicWatch row = watch(topic);
		assertEquals(1, row.getAlertCount(), "升级后 alertCount 应置 1");
		assertTrue(row.getId().equals(warned.getId()), "应更新原 watch 行而非新建");
	}

	@Test
	@DisplayName("T_fuse_12 告警链形态：fuse topic 前缀工具 + 系统消息判定")
	void t12_helpersShape() {
		assertTrue(BusFuseService.isFuseTopic("[fuse]abc"));
		assertFalse(BusFuseService.isFuseTopic("fuse-plain"));
		assertFalse(BusFuseService.isFuseTopic(null));
		String longTopic = "x".repeat(120);
		String built = BusFuseService.buildAlertTopic(longTopic);
		assertEquals(100, built.length(), "超长 topic 截到列宽 100");
		assertTrue(built.startsWith(BusFuseService.ALERT_TOPIC_PREFIX));
		AgentMessage sys = new AgentMessage();
		sys.setFromAgentId(0L);
		assertTrue(fuseService.isSystemMessage(sys), "from=0 视为系统消息");
	}

	@Test
	@DisplayName("T_fuse_13 消息级 0 档=无需回音：不告警、无 watch 锚点、不销案（bus-fuse-zero-optout）")
	void t13_zeroMinutesNoFuse() {
		// 0 档末条：显式无需回音 → 不告警、不登记 watch（严禁当 0 分钟超时立即告警）
		String topic = "fuse-demo-z1";
		insertMessage(INITIATOR, WORKER, topic, 0, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(0, countAlerts(topic), "0 档显式无需回音不应告警");
		assertNull(watch(topic), "0 档不应登记 watch 锚点");
		// 0 档后的新正常消息成为新末条 → 等待段正常计时告警（跳过分支不残留）
		insertMessage(INITIATOR, WORKER, topic, 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic), "0 档之后的新等待段应正常告警");
		// 0 档不销案：已告警等待段的 watch 行不被 0 档新末条清除，也不重复告警
		String topic2 = "fuse-demo-z2";
		insertMessage(INITIATOR, WORKER, topic2, 1, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic2));
		assertNotNull(watch(topic2));
		insertMessage(INITIATOR, WORKER, topic2, 0, EXPIRED_MS);
		fuseService.scanOnce();
		assertEquals(1, countAlerts(topic2), "0 档新末条不应触发新告警");
		assertNotNull(watch(topic2), "0 档跳过分支不应销案（watch 保留）");
	}

	// ===================================================================
	// 频控 mock（与 BusM1ApiTest 同款假 Redis；fuse xadd 走 deep stub 不落真队列）
	// ===================================================================

	@TestConfiguration
	static class FakeRedisConfig {

		@Bean
		@Primary
		@SuppressWarnings({"unchecked", "rawtypes"})
		public StringRedisTemplate fakeStringRedisTemplate() {
			StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
			ValueOperations<String, String> valueOps = mock(ValueOperations.class);
			ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
			when(template.opsForValue()).thenReturn(valueOps);
			when(valueOps.increment(anyString())).thenAnswer(inv -> {
				String key = inv.getArgument(0);
				if (key.contains("agent-bus:rate:")) {
					return 1L;
				}
				return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet() <= 60 ? 1L : 61L;
			});
			return template;
		}
	}
}
