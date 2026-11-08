package com.youlxb2008.admin.modules.bus.fuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Date;
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
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusTopicWatch;
import com.youlxb2008.admin.modules.bus.mapper.AgentMessageMapper;
import com.youlxb2008.admin.modules.bus.mapper.BusTopicWatchMapper;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;

/**
 * BUS_ALERT_SESSION 未配置形态的保险丝行为（bus-watchdog-fuse ①降级分支）：
 * 超时发现仅 warn 落表（watch 锚点登记、alertCount=0），不生成告警消息；
 * 同等待段多轮扫描不重复 warn。
 */
@SpringBootTest(classes = { WebApplication.class, BusFuseNoSessionTest.FakeRedisConfig.class }, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:busfnowarn;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-bus-fuse-nowarn",
		"bus.dispatch.enabled=false",
		"bus.fuse.scan-interval-ms=-1",
		"bus.fuse.default.minutes=1" })
@Transactional
@Rollback(true)
class BusFuseNoSessionTest {

	private static final long INITIATOR = 1L;
	private static final long WORKER = 2L;
	private static final String TOPIC = "nowarn-demo";

	@Autowired
	private AgentMessageMapper messageMapper;

	@Autowired
	private BusTopicWatchMapper watchMapper;

	@Autowired
	private BusFuseService fuseService;

	@BeforeEach
	void clean() {
		messageMapper.delete(Wrappers.<AgentMessage>lambdaQuery().like(AgentMessage::getTopic, "nowarn"));
		watchMapper.delete(Wrappers.<BusTopicWatch>lambdaQuery().like(BusTopicWatch::getTopic, "nowarn"));
	}

	private void insertExpired() {
		AgentMessage row = new AgentMessage();
		row.setMessageId(UUID.randomUUID().toString());
		row.setFromAgentId(INITIATOR);
		row.setToAgentId(WORKER);
		row.setMsgType("notify");
		row.setTopic(TOPIC);
		row.setTitle("t");
		row.setStatus(BusMessageService.STATUS_DELIVERED);
		row.setRetryCount(0);
		Date at = new Date(System.currentTimeMillis() - 3L * 60L * 1000L);
		row.setCreateTime(at);
		row.setUpdateTime(at);
		messageMapper.insert(row);
	}

	@Test
	@DisplayName("T_fuse_n1 BUS_ALERT_SESSION 未配置：仅落表 warn，不生成告警消息")
	void t01_warnOnlyNoAlertRow() {
		insertExpired();
		fuseService.scanOnce();
		Long alerts = messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getTopic, BusFuseService.ALERT_TOPIC_PREFIX + TOPIC));
		assertEquals(0L, alerts, "未配置告警落点不应生成告警消息");
		BusTopicWatch row = watchMapper.selectOne(Wrappers.<BusTopicWatch>lambdaQuery()
				.eq(BusTopicWatch::getTopic, TOPIC));
		assertNotNull(row, "发现超时应登记 watch（锚点）");
		assertEquals(0, row.getAlertCount(), "alertCount=0 标记 warn-only");
		assertNotNull(row.getAlertedMessageId(), "锚点应登记");
		assertNull(row.getLastAlertAt(), "未投递无告警时刻");
	}

	@Test
	@DisplayName("T_fuse_n2 未配置形态多轮扫描不重复 warn（同段去重）")
	void t02_warnOnlyDedup() {
		insertExpired();
		fuseService.scanOnce();
		fuseService.scanOnce();
		Long watchRows = watchMapper.selectCount(Wrappers.<BusTopicWatch>lambdaQuery()
				.eq(BusTopicWatch::getTopic, TOPIC));
		assertEquals(1L, watchRows, "同等待段只登记一次");
		Long alerts = messageMapper.selectCount(Wrappers.<AgentMessage>lambdaQuery()
				.eq(AgentMessage::getTopic, BusFuseService.ALERT_TOPIC_PREFIX + TOPIC));
		assertEquals(0L, alerts);
	}

	// ===================================================================
	// 频控 mock（与 BusM1ApiTest 同款假 Redis）
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
			when(valueOps.increment(anyString())).thenAnswer(inv -> 1L);
			return template;
		}
	}
}
