package com.youlxb2008.admin.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.utils.jwt.JwtConstants;

import jakarta.annotation.Resource;

/**
 * M5.4-R1 删除语义修正 — 路由级集成测试（MockMvc + 独立内存 H2）
 * <p>
 * 对照任务书 docs/任务排期/M5.4-R1-key-hard-delete-agent-revoke.md 的验收项：
 * <ul>
 *   <li>DELETE /api/agent/admin/agents/{id} → 404 占位（agent 不支持删除，吊销走 PUT status）</li>
 *   <li>PUT /api/agent/admin/keys/{id}/revoke → 404 占位（key 删除走 DELETE /keys/{id}）</li>
 *   <li>DELETE /api/agent/admin/keys/{id} → 200 物理删除（getById 为 null）；不存在 → 500（BusinessException）</li>
 * </ul>
 * 管理端接口走 JWT：@BeforeEach 以 data.sql 内置 super_admin（admin/123456，userType=0
 * 权限全放行）登录取 Authorization token。使用独立内存 H2，全部 @Transactional 回滚。
 */
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcdel;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与笔记文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-del",
		"notes.root=./target/test-notes-del" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class AgentDeleteSemanticsV541Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Resource
	private AgentService agentService;

	@Resource
	private ApiKeyService apiKeyService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent agent;
	private Long keyId;
	private String token;

	@BeforeEach
	void seed() throws Exception {
		String tag = "t" + SEQ.incrementAndGet();
		agent = agentService.createAgent("del_" + tag, "删除语义" + tag, null, "测试 agent", 1, 1L);
		// 生成一把 key（物理删除用例的数据源）
		ApiKeyService.GeneratedKey generated = apiKeyService.createKey(agent.getId(), List.of("notes:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
		keyId = generated.getKeyId();
		assertNotNull(keyId, "key 应已生成");
		// super_admin 登录（data.sql 内置 admin/123456，userType=0 全权限放行）
		MvcResult loginResult = mockMvc.perform(post("/system/user/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"admin\",\"password\":\"123456\"}"))
				.andExpect(status().isOk())
				.andReturn();
		token = loginResult.getResponse().getHeader(JwtConstants.HEADER);
		assertNotNull(token, "登录应返回 access token");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> bodyOf(MvcResult result) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
	}

	// ===================================================================
	// 占位 404：agent 删除 / key 吊销已下线
	// ===================================================================

	@Test
	@DisplayName("T_del_01 DELETE /api/agent/admin/agents/{id} → 404 占位（agent 不支持删除）")
	void t_del01_deleteAgentGone() throws Exception {
		MvcResult result = mockMvc.perform(delete("/api/agent/admin/agents/" + agent.getId())
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isNotFound())
				.andReturn();
		Map<String, Object> body = bodyOf(result);
		assertEquals("false", String.valueOf(body.get("success")), "占位响应 success 应为 false");
		assertTrue(String.valueOf(body.get("msg")).contains("接口已下线"), "应提示接口已下线");
		// agent 未被删除（占位接口无副作用）
		assertNotNull(agentService.getById(agent.getId()), "agent 应仍然存在");
	}

	@Test
	@DisplayName("T_del_02 PUT /api/agent/admin/keys/{id}/revoke → 404 占位（吊销已下线）")
	void t_del02_revokeKeyGone() throws Exception {
		MvcResult result = mockMvc.perform(put("/api/agent/admin/keys/" + keyId + "/revoke")
				.header(JwtConstants.HEADER, token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"test\"}"))
				.andExpect(status().isNotFound())
				.andReturn();
		Map<String, Object> body = bodyOf(result);
		assertEquals("false", String.valueOf(body.get("success")), "占位响应 success 应为 false");
		assertTrue(String.valueOf(body.get("msg")).contains("接口已下线"), "应提示接口已下线");
		// key 未被删除（占位接口无副作用）
		assertNotNull(apiKeyService.getById(keyId), "key 应仍然存在");
	}

	// ===================================================================
	// key 硬删除：DELETE /keys/{id}
	// ===================================================================

	@Test
	@DisplayName("T_del_03 DELETE /api/agent/admin/keys/{id} → 200 物理删除（getById 为 null）")
	void t_del03_deleteKeyHardDelete() throws Exception {
		mockMvc.perform(delete("/api/agent/admin/keys/" + keyId)
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isOk());
		assertNull(apiKeyService.getById(keyId), "key 应被物理删除（索引行不存在）");
	}

	@Test
	@DisplayName("T_del_04 DELETE /api/agent/admin/keys/{id}（不存在/重复删除）→ 500（BusinessException）")
	void t_del04_deleteKeyNotFound() throws Exception {
		MvcResult result = mockMvc.perform(delete("/api/agent/admin/keys/999999")
				.header(JwtConstants.HEADER, token))
				.andExpect(status().isInternalServerError())
				.andReturn();
		Map<String, Object> body = bodyOf(result);
		assertEquals("false", String.valueOf(body.get("success")), "业务异常 success 应为 false");
		// 全局异常处理器对 BusinessException 统一脱敏为「服务异常」（真实消息「API Key 不存在」记日志）
		assertEquals("服务异常", body.get("msg"));
	}
}
