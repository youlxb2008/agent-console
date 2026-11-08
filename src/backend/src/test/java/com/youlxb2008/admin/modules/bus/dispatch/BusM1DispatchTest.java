package com.youlxb2008.admin.modules.bus.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.lettuce.core.RedisCommandExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.service.BusApiTargetConfig;
import com.youlxb2008.admin.modules.bus.service.BusChannelRouteService;
import com.youlxb2008.admin.modules.bus.service.BusEnvelopeFormatter;
import com.youlxb2008.admin.modules.bus.service.BusMessageService;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig;
import com.youlxb2008.admin.modules.bus.service.BusRouteConfig.Route;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroup;
import org.springframework.data.redis.connection.stream.StreamInfo.XInfoGroups;
import org.springframework.data.redis.core.StreamOperations;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;

/**
 * M1 dispatcher 纯逻辑测试（不起 Spring 上下文，全部 Mockito mock）
 * <p>
 * 覆盖：信封格式（方案 §7）/ 路由解析（BUS_TARGET 行，models 白名单逻辑）/
 * 指数退避序列 / 自动 chatId 合法性（chat_id 字符集 ^[A-Za-z0-9_:-]{1,64}$）/
 * 消费侧幂等决策（status≠0 → XACK 跳过不投递）/ 投递失败 → retry_count+1 + last_error /
 * ensureGroup 先查后建（组存在跳过 / 不存在建组 / Lettuce cause 链 BUSYGROUP 吞错 / 真故障重抛）。
 */
class BusM1DispatchTest {

	// ===================================================================
	// 信封格式（方案 §7）
	// ===================================================================

	@Test
	@DisplayName("T_dsp_01 信封格式：首行前缀 + 标题 + payload 三段")
	void t01_envelopeFormat() {
		String envelope = BusEnvelopeFormatter.format("Delta", 5, "alert", "alpha-exception",
				"msg-uuid-123", "model-a", "示例标题·冲突挂起", "{\"detail\":\"ok\"}");
		String firstLine = envelope.split("\n", 2)[0];
		assertEquals("[来源:Delta(agent:5)|类型:alert|主题:alpha-exception|消息:msg-uuid-123|模型:model-a]",
				firstLine, "首行前缀须与方案 §7 一致");
		assertTrue(envelope.contains("示例标题·冲突挂起"), "第二段为标题");
		assertTrue(envelope.endsWith("{\"detail\":\"ok\"}"), "第三段为 payload JSON");
	}

	@Test
	@DisplayName("T_dsp_02 信封：model 空 → 模型:默认；payload 空 notify 追加处置指引（busfix-1007）")
	void t02_envelopeDefaults() {
		String envelope = BusEnvelopeFormatter.format("Alpha", 1, "notify", "t", "mid", null, "标题", null);
		assertTrue(envelope.contains("|模型:默认]"), "未指定模型应显示「默认」");
		assertFalse(envelope.contains("\n\n"), "payload 为空不应有空行");
		String[] parts = envelope.split("\n");
		assertEquals(3, parts.length, "payload 空 notify 应为前缀+标题+处置指引三段（busfix-1007）");
		assertTrue(parts[2].equals(BusEnvelopeFormatter.DELIVERY_HINT), "第三段应为处置指引行");
		// alert 不追加指引行：payload 空 alert 维持前缀+标题两段（busfix-1007 口径）
		String alertEnvelope = BusEnvelopeFormatter.format("Alpha", 1, "alert", "t", "mid", null, "标题", null);
		assertEquals(2, alertEnvelope.split("\n").length, "payload 空 alert 应只有前缀+标题两段");
	}

	@Test
	@DisplayName("T_dsp_15 处置指引：notify 文末追加（在 payload 之后）；alert 不追加（busfix-1007）")
	void t15_deliveryHint() {
		String notify = BusEnvelopeFormatter.format("Delta", 5, "notify", "bp-x", "mid-15", null, "交付回执",
				"{\"content\":\"正文\"}");
		assertTrue(notify.endsWith(BusEnvelopeFormatter.DELIVERY_HINT), "notify 指引行应在文末");
		assertTrue(notify.contains("{\"content\":\"正文\"}\n" + BusEnvelopeFormatter.DELIVERY_HINT),
				"指引行应在 payload 之后");
		String alert = BusEnvelopeFormatter.format("Delta", 5, "alert", "t", "mid-15b", null, "告警", null);
		assertFalse(alert.contains(BusEnvelopeFormatter.DELIVERY_HINT), "alert 不应追加指引行");
	}

	// ===================================================================
	// 路由解析与 model 白名单
	// ===================================================================

	@Test
	@DisplayName("T_dsp_03 路由行解析：四段/三段（models 缺省=*）/非法输入")
	void t03_routeParse() {
		Route r1 = BusRouteConfig.parseLine("1|ws://your-host:8765|sec1|models=*");
		assertEquals(1, r1.toAgentId());
		assertEquals("http://your-host:8765/webui/bootstrap", r1.httpBaseUrl() + "/webui/bootstrap");
		assertEquals(Set.of("*"), r1.models());

		Route r2 = BusRouteConfig.parseLine("2|wss://gw.example.com|sec2|models=model-a,model-b");
		assertEquals(Set.of("model-a", "model-b"), r2.models());
		assertTrue(r2.httpBaseUrl().startsWith("https://"), "wss → https");

		Route r3 = BusRouteConfig.parseLine("3|ws://h:1|sec3");
		assertEquals(Set.of("*"), r3.models(), "models 段缺省 = 不限");

		boolean threw = false;
		try {
			BusRouteConfig.parseLine("abc|ws://h:1|sec");
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		assertTrue(threw, "toId 非数字应抛");

		threw = false;
		try {
			BusRouteConfig.parseLine("1|http://h:1|sec");
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		assertTrue(threw, "非 ws scheme 应抛");
	}

	@Test
	@DisplayName("T_dsp_04 modelAllowed：* 不限 / 白名单忽略大小写 / 未指定 model 放行 / 无路由拒绝")
	void t04_modelAllowed() {
		BusRouteConfig config = new BusRouteConfig(fixedEnv(Map.of(
				"bus.target.1", "1|ws://h:1|sec|models=model-a,model-b",
				"bus.target.2", "2|ws://h:2|sec|models=*")));
		assertTrue(config.modelAllowed(1, "model-b"));
		assertTrue(config.modelAllowed(1, "MODEL-B"), "白名单忽略大小写");
		assertFalse(config.modelAllowed(1, "other-model"));
		assertTrue(config.modelAllowed(1, null), "未指定模型（收件人默认）不受白名单约束");
		assertTrue(config.modelAllowed(2, "anything"), "models=* 不限");
		assertFalse(config.modelAllowed(9, "model-b"), "无路由行拒绝");
		assertFalse(config.hasRoute(9));
		assertTrue(config.hasRoute(1));
		assertEquals("ac:agent-bus:stream:1", BusRouteConfig.streamKey(1));
	}

	// ===================================================================
	// 指数退避与自动会话
	// ===================================================================

	@Test
	@DisplayName("T_dsp_05 指数退避序列 1s/2s/4s/8s/16s（越界取尾档）")
	void t05_backoff() {
		assertEquals(1, BusMessageService.backoffMillis(1));
		assertEquals(2, BusMessageService.backoffMillis(2));
		assertEquals(4, BusMessageService.backoffMillis(3));
		assertEquals(8, BusMessageService.backoffMillis(4));
		assertEquals(16, BusMessageService.backoffMillis(5));
		assertEquals(16, BusMessageService.backoffMillis(6), "越界取最后一档");
		assertEquals(1, BusMessageService.backoffMillis(0));
	}

	@Test
	@DisplayName("T_dsp_06 自动 chatId 固定复用（bus-agent{from}-{type} 无时间戳；匹配 nanobot chat_id 字符集）")
	void t06_autoChatId() {
		AgentMessage row = new AgentMessage();
		row.setFromAgentId(5L);
		row.setMsgType("alert");
		String chatId = BusDispatchService.autoChatId(row);
		assertEquals("bus-agent5-alert", chatId, "同源同类型应恒定复用同一会话（无时间戳后缀）");
		assertTrue(chatId.matches("^bus-[A-Za-z0-9_:-]{1,56}$"), "须匹配总线命名空间约束: " + chatId);
		assertTrue(chatId.matches("^[A-Za-z0-9_:-]{1,64}$"), "须匹配 nanobot chat_id 合法字符集");
	}

	// ===================================================================
	// 消费侧幂等与失败路径（mock 依赖，不起 WS）
	// ===================================================================

	@SuppressWarnings("unchecked")
	private BusDispatchService newDispatchService(BusMessageService messageService) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
		when(provider.getIfAvailable()).thenReturn(template);
		BusRouteConfig routeConfig = new BusRouteConfig(fixedEnv(Map.of(
				"bus.target.1", "1|ws://127.0.0.1:1/mock|sec|models=*")));
		BusChannelRouteService channelRouteService = mock(BusChannelRouteService.class);
		when(channelRouteService.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_WS);
		BusApiTargetConfig apiTargets = mock(BusApiTargetConfig.class);
		when(apiTargets.targetOf(anyLong())).thenReturn(Optional.empty());
		return new BusDispatchService(routeConfig, messageService, mock(AgentMapper.class), provider,
				channelRouteService, apiTargets, 150000L, false);
	}
	private MapRecord<String, Object, Object> record(String messageId) {
		Map<Object, Object> value = Map.of(
				"message_id", messageId,
				"from", "5",
				"to", "1",
				"type", "alert",
				"topic", "alpha-exception",
				"title", "标题",
				"payload", "{}",
				"ts", "1");
		return MapRecord.create("ac:agent-bus:stream:1", value).withId(RecordId.autoGenerate());
	}

	@Test
	@DisplayName("T_dsp_07 消费侧幂等：status=1 → XACK 跳过不投递")
	void t07_idempotentSkip() {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage delivered = new AgentMessage();
		delivered.setMessageId("mid-1");
		delivered.setStatus(BusMessageService.STATUS_DELIVERED);
		when(messageService.findByMessageId("mid-1")).thenReturn(delivered);
		BusDispatchService service = newDispatchService(messageService);
		service.handleRecord(route1(), record("mid-1"));
		// 已投递：不更新表（无 retry 无成功回填）
		verify(messageService, never()).updateRow(any());
	}

	@Test
	@DisplayName("T_dsp_08 表无对应行（脏数据）→ XACK 丢弃不投递")
	void t08_missingRowSkip() {
		BusMessageService messageService = mock(BusMessageService.class);
		when(messageService.findByMessageId("mid-miss")).thenReturn(null);
		BusDispatchService service = newDispatchService(messageService);
		service.handleRecord(route1(), record("mid-miss"));
		verify(messageService, never()).updateRow(any());
	}

	@Test
	@DisplayName("T_dsp_09 投递失败（WS 不可达）→ retry_count+1 + last_error")
	void t09_deliveryFailureIncrementsRetry() {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage pending = new AgentMessage();
		pending.setId(100L);
		pending.setMessageId("mid-fail");
		pending.setFromAgentId(5L);
		pending.setToAgentId(1L);
		pending.setMsgType("alert");
		pending.setTopic("alpha-exception");
		pending.setTitle("标题");
		pending.setStatus(BusMessageService.STATUS_PENDING);
		pending.setRetryCount(0);
		when(messageService.findByMessageId("mid-fail")).thenReturn(pending);
		BusDispatchService service = newDispatchService(messageService);
		service.handleRecord(route1(), record("mid-fail"));
		org.mockito.ArgumentCaptor<AgentMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
		verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(1, patch.getRetryCount(), "首次失败 retry_count 应为 1");
		assertNotNull(patch.getLastError(), "应记录 last_error");
		assertNull(patch.getStatus(), "未达 5 次不应终态化");
		assertTrue(patch.getLastError().contains("connect failed"), "连接失败应计入 last_error");
	}

	@Test
	@DisplayName("T_dsp_10 失败达 5 次 → status=3 终态")
	void t10_retryExhaustedFailed() {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage pending = new AgentMessage();
		pending.setId(101L);
		pending.setMessageId("mid-exh");
		pending.setFromAgentId(5L);
		pending.setToAgentId(1L);
		pending.setMsgType("notify");
		pending.setTopic("t");
		pending.setTitle("标题");
		pending.setStatus(BusMessageService.STATUS_PENDING);
		pending.setRetryCount(4);
		when(messageService.findByMessageId("mid-exh")).thenReturn(pending);
		BusDispatchService service = newDispatchService(messageService);
		service.handleRecord(route1(), record("mid-exh"));
		org.mockito.ArgumentCaptor<AgentMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
		verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(5, patch.getRetryCount());
		assertEquals(BusMessageService.STATUS_FAILED, patch.getStatus(), "第 5 次失败应置 3");
	}

	// ===================================================================
	// ensureGroup 先查后建（v2 修复：Lettuce 把 BUSYGROUP 包成
	// RedisCommandExecutionException("Error in execution")，getMessage() 匹配失效 →
	// 组已存在时每轮 createGroup 必抛 → 消费循环死循环；Redis Stream 实证）
	// ===================================================================

	/** ensureGroup 测试上下文：service + 暴露的 StreamOperations mock */
	@SuppressWarnings("unchecked")
	private record DispatchWithOps(BusDispatchService service,
			StreamOperations<String, Object, Object> ops) {
	}

	@SuppressWarnings("unchecked")
	private DispatchWithOps dispatchWithOps(BusMessageService messageService) {
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
		when(provider.getIfAvailable()).thenReturn(template);
		BusRouteConfig routeConfig = new BusRouteConfig(fixedEnv(Map.of(
				"bus.target.1", "1|ws://127.0.0.1:1/mock|sec|models=*")));
		BusChannelRouteService channelRouteService = mock(BusChannelRouteService.class);
		when(channelRouteService.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_WS);
		BusApiTargetConfig apiTargets = mock(BusApiTargetConfig.class);
		when(apiTargets.targetOf(anyLong())).thenReturn(Optional.empty());
		BusDispatchService service = new BusDispatchService(routeConfig, messageService,
				mock(AgentMapper.class), provider, channelRouteService, apiTargets, 150000L, false);
		StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
		when(template.opsForStream()).thenReturn(streamOps);
		return new DispatchWithOps(service, streamOps);
	}

	@Test
	@DisplayName("T_dsp_11 组已存在（XINFO GROUPS 命中）→ 跳过 createGroup（先查后建，根修死循环）")
	void t11_ensureGroupSkipsWhenGroupExists() {
		DispatchWithOps ctx = dispatchWithOps(mock(BusMessageService.class));
		XInfoGroup existing = mock(XInfoGroup.class);
		when(existing.groupName()).thenReturn(BusDispatchService.GROUP);
		XInfoGroups groups = mock(XInfoGroups.class);
		when(groups.iterator()).thenReturn(List.of(existing).iterator());
		when(ctx.ops().groups("ac:agent-bus:stream:1")).thenReturn(groups);

		ctx.service().ensureGroup("ac:agent-bus:stream:1");

		verify(ctx.ops(), never()).createGroup(anyString(), any(ReadOffset.class), anyString());
	}

	@Test
	@DisplayName("T_dsp_12 组不存在（XINFO 抛异常=stream 未建）→ createGroup 正常建组")
	void t12_ensureGroupCreatesWhenMissing() {
		DispatchWithOps ctx = dispatchWithOps(mock(BusMessageService.class));
		when(ctx.ops().groups(anyString())).thenThrow(new RuntimeException("ERR no such key"));
		when(ctx.ops().createGroup(anyString(), any(ReadOffset.class), eq(BusDispatchService.GROUP)))
				.thenReturn("1-0");

		ctx.service().ensureGroup("ac:agent-bus:stream:1");

		verify(ctx.ops()).createGroup(eq("ac:agent-bus:stream:1"), any(ReadOffset.class),
				eq(BusDispatchService.GROUP));
	}

	@Test
	@DisplayName("T_dsp_13 createGroup 抛 Lettuce 形态（message=Error in execution，BUSYGROUP 在 cause 链）→ 吞掉不抛")
	void t13_ensureGroupSwallowsBusyGroupFromCauseChain() {
		DispatchWithOps ctx = dispatchWithOps(mock(BusMessageService.class));
		when(ctx.ops().groups(anyString())).thenThrow(new RuntimeException("ERR no such key"));
		RedisCommandExecutionException wrapped = new RedisCommandExecutionException("Error in execution",
				new RedisCommandExecutionException("BUSYGROUP Consumer Group name already exists"));
		when(ctx.ops().createGroup(anyString(), any(ReadOffset.class), eq(BusDispatchService.GROUP)))
				.thenThrow(wrapped);

		ctx.service().ensureGroup("ac:agent-bus:stream:1");

		verify(ctx.ops()).createGroup(anyString(), any(ReadOffset.class), eq(BusDispatchService.GROUP));
	}

	@Test
	@DisplayName("T_dsp_14 createGroup 抛非 BUSYGROUP 异常 → 原样重抛（真故障不静默）")
	void t14_ensureGroupRethrowsOtherErrors() {
		DispatchWithOps ctx = dispatchWithOps(mock(BusMessageService.class));
		when(ctx.ops().groups(anyString())).thenThrow(new RuntimeException("ERR no such key"));
		RedisCommandExecutionException real = new RedisCommandExecutionException("Error in execution",
				new RuntimeException("MOVED 1234 127.0.0.1:6379"));
		when(ctx.ops().createGroup(anyString(), any(ReadOffset.class), eq(BusDispatchService.GROUP)))
				.thenThrow(real);

		assertThrows(RedisCommandExecutionException.class,
				() -> ctx.service().ensureGroup("ac:agent-bus:stream:1"));
	}

	// ===================================================================
	// busfix-2 worker 回执定向续报（resolveWindowTarget + deliverRow 定向改写）
	// ===================================================================

	private static final String WINDOW_UUID = "820005d9-b273-4f04-96ab-608c850b8945";

	/** worker（agent 5）→管理员 agent（alpha）的回执行桩（messageId=mid-win） */
	private AgentMessage workerReplyRow(String topic) {
		AgentMessage row = new AgentMessage();
		row.setId(200L);
		row.setMessageId("mid-win");
		row.setFromAgentId(5L);
		row.setToAgentId(1L);
		row.setMsgType("notify");
		row.setTopic(topic);
		row.setTitle("回执");
		row.setPayload("{\"content\":\"done\"}");
		row.setStatus(BusMessageService.STATUS_PENDING);
		return row;
	}

	@Test
	@DisplayName("T_dsp_16 定向命中：同 topic 最早 from=1 派活行 payload.window → 返回裸 uuid")
	void t16_windowTargetHit() {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage dispatchRow = new AgentMessage();
		dispatchRow.setFromAgentId(1L);
		dispatchRow.setPayload("{\"content\":\"干活\",\"window\":\"" + WINDOW_UUID + "\"}");
		when(messageService.findEarliestYunzhiRowByTopic("joke-evt")).thenReturn(dispatchRow);
		BusDispatchService service = newDispatchService(messageService);
		assertEquals(WINDOW_UUID, service.resolveWindowTarget(route1(), workerReplyRow("joke-evt")));
	}

	@Test
	@DisplayName("T_dsp_17 未命中集：无派活行 / payload 无 window / 非法 JSON / window 非 uuid → null 现行为")
	void t17_windowTargetMiss() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusDispatchService service = newDispatchService(messageService);
		// 无派活行
		when(messageService.findEarliestYunzhiRowByTopic("t-none")).thenReturn(null);
		assertNull(service.resolveWindowTarget(route1(), workerReplyRow("t-none")));
		// payload 无 window 字段
		AgentMessage noWindow = new AgentMessage();
		noWindow.setFromAgentId(1L);
		noWindow.setPayload("{\"content\":\"x\"}");
		when(messageService.findEarliestYunzhiRowByTopic("t-nowin")).thenReturn(noWindow);
		assertNull(service.resolveWindowTarget(route1(), workerReplyRow("t-nowin")));
		// payload 非法 JSON（容错按未命中）
		AgentMessage bad = new AgentMessage();
		bad.setFromAgentId(1L);
		bad.setPayload("not-json");
		when(messageService.findEarliestYunzhiRowByTopic("t-bad")).thenReturn(bad);
		assertNull(service.resolveWindowTarget(route1(), workerReplyRow("t-bad")));
		// window 非 uuid 格式（拒自由字符串，防误投新建会话）
		AgentMessage badWin = new AgentMessage();
		badWin.setFromAgentId(1L);
		badWin.setPayload("{\"window\":\"not-a-uuid\"}");
		when(messageService.findEarliestYunzhiRowByTopic("t-badwin")).thenReturn(badWin);
		assertNull(service.resolveWindowTarget(route1(), workerReplyRow("t-badwin")));
	}

	@Test
	@DisplayName("T_dsp_18 触发边界：from=1（管理员自消息）与 to≠1 目标循环均不查询 → null")
	void t18_windowTargetBoundary() {
		BusMessageService messageService = mock(BusMessageService.class);
		BusDispatchService service = newDispatchService(messageService);
		AgentMessage self = workerReplyRow("t-self");
		self.setFromAgentId(1L);
		assertNull(service.resolveWindowTarget(route1(), self));
		Route route5 = new Route(5, "ws://127.0.0.1:1/mock", "sec", Set.of("*"));
		assertNull(service.resolveWindowTarget(route5, workerReplyRow("t-r5")));
		verify(messageService, never()).findEarliestYunzhiRowByTopic(anyString());
	}

	@SuppressWarnings("unchecked")
	private void injectClient(BusDispatchService service, long toAgentId, BusGatewayClient client) {
		Map<Long, BusGatewayClient> clients = (Map<Long, BusGatewayClient>) org.springframework.test.util.ReflectionTestUtils
				.getField(service, "clients");
		clients.put(toAgentId, client);
	}

	@Test
	@DisplayName("T_dsp_19 定向投递：命中 window → deliver(chatId=window) + 无条件回填 session_key=window + channel=ws")
	void t19_windowDeliveryRewriteAndBackfill() throws Exception {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage row = workerReplyRow("joke-evt");
		when(messageService.findByMessageId("mid-win")).thenReturn(row);
		AgentMessage dispatchRow = new AgentMessage();
		dispatchRow.setFromAgentId(1L);
		dispatchRow.setPayload("{\"window\":\"" + WINDOW_UUID + "\"}");
		when(messageService.findEarliestYunzhiRowByTopic("joke-evt")).thenReturn(dispatchRow);
		BusDispatchService service = newDispatchService(messageService);
		BusGatewayClient client = mock(BusGatewayClient.class);
		injectClient(service, 1L, client);
		service.handleRecord(route1(), record("mid-win"));
		org.mockito.ArgumentCaptor<AgentMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
		verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(BusMessageService.STATUS_DELIVERED, patch.getStatus());
		assertEquals(BusRoute.CHANNEL_WS, patch.getChannel());
		assertEquals(WINDOW_UUID, patch.getSessionKey(), "定向模式应无条件回填实际投递窗口");
		verify(client).deliver(eq(WINDOW_UUID), anyString(), any());
	}

	@Test
	@DisplayName("T_dsp_20 定向强制 ws：resolveChannel=api 且 target 缺配置 + window 命中 → 绕过 api 降级逻辑直接 ws")
	void t20_windowForcesWs() {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage row = workerReplyRow("joke-evt");
		when(messageService.findByMessageId("mid-win")).thenReturn(row);
		AgentMessage dispatchRow = new AgentMessage();
		dispatchRow.setFromAgentId(1L);
		dispatchRow.setPayload("{\"window\":\"" + WINDOW_UUID + "\"}");
		when(messageService.findEarliestYunzhiRowByTopic("joke-evt")).thenReturn(dispatchRow);
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		StringRedisTemplate template = mock(StringRedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
		when(provider.getIfAvailable()).thenReturn(template);
		BusRouteConfig routeConfig = new BusRouteConfig(fixedEnv(Map.of(
				"bus.target.1", "1|ws://127.0.0.1:1/mock|sec|models=*")));
		BusChannelRouteService channelRouteService = mock(BusChannelRouteService.class);
		when(channelRouteService.resolveChannel(anyLong(), anyLong())).thenReturn(BusRoute.CHANNEL_API);
		BusApiTargetConfig apiTargets = mock(BusApiTargetConfig.class);
		when(apiTargets.targetOf(anyLong())).thenReturn(Optional.empty());
		BusDispatchService service = new BusDispatchService(routeConfig, messageService,
				mock(AgentMapper.class), provider, channelRouteService, apiTargets, 150000L, false);
		BusGatewayClient client = mock(BusGatewayClient.class);
		injectClient(service, 1L, client);
		service.handleRecord(route1(), record("mid-win"));
		org.mockito.ArgumentCaptor<AgentMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
		verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals(BusMessageService.STATUS_DELIVERED, patch.getStatus());
		assertEquals(BusRoute.CHANNEL_WS, patch.getChannel());
		assertNotEquals(BusDispatchService.API_FALLBACK_NOTE, patch.getLastError(),
				"定向模式不应触发 api 降级备注（绕过 t_bus_route api 配置）");
		assertEquals(WINDOW_UUID, patch.getSessionKey());
	}

	@Test
	@DisplayName("T_dsp_21 常规回执零变化：无 window → deliver(chatId=autoChatId) + 空才回填现行为")
	void t21_noWindowUnchanged() throws Exception {
		BusMessageService messageService = mock(BusMessageService.class);
		AgentMessage row = workerReplyRow("t-plain");
		when(messageService.findByMessageId("mid-win")).thenReturn(row);
		when(messageService.findEarliestYunzhiRowByTopic(anyString())).thenReturn(null);
		BusDispatchService service = newDispatchService(messageService);
		BusGatewayClient client = mock(BusGatewayClient.class);
		injectClient(service, 1L, client);
		service.handleRecord(route1(), record("mid-win"));
		org.mockito.ArgumentCaptor<AgentMessage> captor = org.mockito.ArgumentCaptor.forClass(AgentMessage.class);
		verify(messageService).updateRow(captor.capture());
		AgentMessage patch = captor.getValue();
		assertEquals("bus-agent5-notify", patch.getSessionKey(), "无 window 常规回执维持自动会话回填");
		assertEquals(BusRoute.CHANNEL_WS, patch.getChannel());
		verify(client).deliver(eq("bus-agent5-notify"), anyString(), any());
	}

	private Route route1() {
		return new Route(1, "ws://127.0.0.1:1/mock", "sec", Set.of("*"));
	}

	/** 固定属性 Environment 桩（Mockito， getProperty 按映射返回） */
	@SuppressWarnings("unchecked")
	private org.springframework.core.env.Environment fixedEnv(Map<String, String> props) {
		org.springframework.core.env.Environment env = mock(org.springframework.core.env.Environment.class);
		when(env.getProperty(anyString())).thenAnswer(inv -> props.get((String) inv.getArgument(0)));
		return env;
	}
}
