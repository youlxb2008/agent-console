package com.youlxb2008.admin.modules.agent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.youlxb2008.admin.modules.agent.filter.ApiKeyAuthFilter;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;

import jakarta.annotation.Resource;

/**
 * M3.8 URL 全面 RESTful 化 — 路由级集成测试（MockMvc + 独立内存 H2）
 * <p>
 * 对照任务书 docs/任务排期/M3.8-restful-url.md 的 16 项 T_rest 用例：
 * <ul>
 *   <li>后端可路由验证项 T_rest_05~12 / T_rest_15 / T_rest_16 在本类逐条断言</li>
 *   <li>前端路由/深链项 T_rest_01~04（门户旧 URL 重定向）与 T_rest_13/14（管理端
 *       门户深链拼装）由 portal/管理端 vue-tsc + vite build 与 e2e 复跑覆盖</li>
 * </ul>
 * 覆盖要点：资源 ID 一律走路径（@PathVariable）；旧 query string 形态直接下线 404；
 * nid 格式强校验（^n_[0-9A-Za-z]{10}$）防注入/防枚举；notebook 不存在/已软删 → 404。
 * <p>
 * 使用独立内存 H2 与临时笔记目录，不触碰开发库/真实笔记；全部 @Transactional 回滚。
 */
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcrest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与笔记文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-rest",
		"notes.root=./target/test-notes-rest" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class RestfulV38Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Resource
	private AgentService agentService;

	@Resource
	private NotebookService notebookService;

	@Resource
	private AgentNotebookService agentNotebookService;

	@Resource
	private NoteMetaService noteMetaService;

	@Resource
	private NoteStorageManager noteStorageManager;

	@Resource
	private ApiKeyService apiKeyService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent agent;
	private Notebook nb;
	private String nid;
	private String plainKey;
	private String expectedTitle;
	private String expectedContent;

	@BeforeEach
	void seed() {
		String tag = "t" + SEQ.incrementAndGet();
		agent = agentService.createAgent("ag_" + tag, "显示" + tag, null, "测试 agent", 1, 1L);
		nb = notebookService.create("nb_" + tag, "笔记本" + tag, null, null, null, 1, 1L);
		// agent ↔ notebook 关联（门户主维度；写笔记 D5 前置）
		agentNotebookService.link(agent.getId(), nb.getId(), agent.getName());
		noteMetaService.validateNotebookWrite(agent.getId(), nb.getId());
		expectedTitle = "标题" + tag;
		expectedContent = "正文 md " + tag;
		NoteStorageManager.UpsertResult r = noteStorageManager.upsert(
				agent.getId(), agent.getName(), nb.getId(), "", expectedTitle, expectedContent, "tag1,tag2");
		nid = r.getNid();
		assertNotNull(nid, "upsert 应生成 nid");
		// agent 域接口需要有效 X-API-Key（scopes 覆盖 notes:write + notebook:manage）
		plainKey = apiKeyService.generateKey(agent.getId(),
				List.of("notes:write", "notebook:manage"),
				new Date(System.currentTimeMillis() + 3600_000L));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dataOf(MvcResult result) throws Exception {
		return (Map<String, Object>) objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class).get("data");
	}

	@SuppressWarnings("unchecked")
	private List<Object> dataListOf(MvcResult result) throws Exception {
		return (List<Object>) objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class).get("data");
	}

	// ===================================================================
	// T_rest_05~09：门户 5 个新 RESTful 路径（资源 ID 走路径）→ 200
	// ===================================================================

	@Test
	@DisplayName("T_rest_05 GET /api/portal/notes/{nid} → 200 内容正确（替代 ?nid=）")
	void t_rest05_readNoteByNidPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/portal/notes/" + nid))
				.andExpect(status().isOk())
				.andReturn();
		Map<String, Object> data = dataOf(result);
		assertEquals(nid, data.get("nid"), "nid 应一致");
		assertEquals(expectedTitle, data.get("title"), "标题应一致");
		assertEquals(expectedContent, data.get("contentMd"), "正文应一致");
	}

	@Test
	@DisplayName("T_rest_06 GET /api/portal/notebooks/{id}/notes/search?keyword= → 200（替代 ?notebookId=）")
	void t_rest06_searchNotesNotebookPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/portal/notebooks/" + nb.getId() + "/notes/search")
				.param("keyword", "标题"))
				.andExpect(status().isOk())
				.andReturn();
		List<Object> data = dataListOf(result);
		assertNotNull(data, "搜索结果不应为 null");
		assertEquals(1, data.size(), "应命中刚写入的笔记");
	}

	@Test
	@DisplayName("T_rest_07 GET /api/portal/notebooks/{id}/notes/tags → 200（替代 ?notebookId=）")
	void t_rest07_noteTagsNotebookPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/portal/notebooks/" + nb.getId() + "/notes/tags"))
				.andExpect(status().isOk())
				.andReturn();
		List<Object> data = dataListOf(result);
		assertNotNull(data, "tags 不应为 null");
		assertEquals(2, data.size(), "单篇 tag1,tag2 → 聚合应返回 2 个 tag");
	}

	@Test
	@DisplayName("T_rest_08 GET /api/portal/notebooks/{id}/notes/by-tag?tag= → 200（替代 ?notebookId=）")
	void t_rest08_notesByTagNotebookPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/portal/notebooks/" + nb.getId() + "/notes/by-tag")
				.param("tag", "tag1"))
				.andExpect(status().isOk())
				.andReturn();
		List<Object> data = dataListOf(result);
		assertNotNull(data, "by-tag 结果不应为 null");
		assertEquals(1, data.size(), "按 tag1 精确筛选应命中刚写入的笔记");
	}

	@Test
	@DisplayName("T_rest_09 GET /api/portal/notebooks/{id}/notes/recent → 200（替代 ?notebookId=）")
	void t_rest09_recentNotesNotebookPath() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/portal/notebooks/" + nb.getId() + "/notes/recent"))
				.andExpect(status().isOk())
				.andReturn();
		List<Object> data = dataListOf(result);
		assertNotNull(data, "recent 结果不应为 null");
		assertEquals(1, data.size(), "单篇 recent 应命中");
	}

	// ===================================================================
	// T_rest_10 / T_rest_15：agent 域新 RESTful 路径（X-API-Key）→ 200
	// ===================================================================

	@Test
	@DisplayName("T_rest_10 DELETE /api/agent/notes/{nid} → 200 物理删三件套（替代 ?nid=）")
	void t_rest10_deleteNoteByNidPath() throws Exception {
		mockMvc.perform(delete("/api/agent/notes/" + nid)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey))
				.andExpect(status().isOk());
		// 物理删三件套：索引行 + 文件 + 关联行（同事务内可见）
		assertNull(noteMetaService.findByNid(nid), "删除后索引行应不存在");
	}

	@Test
	@DisplayName("T_rest_15 PUT /api/agent/notebooks/{id} 编辑 → 200（替代 PUT /api/agent/notebooks）")
	void t_rest15_updateNotebookByPath() throws Exception {
		String newName = "nb_renamed_" + SEQ.incrementAndGet();
		mockMvc.perform(put("/api/agent/notebooks/" + nb.getId())
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + newName + "\",\"description\":\"改名\",\"status\":1}"))
				.andExpect(status().isOk());
		Notebook updated = notebookService.getByIdOrNull(nb.getId());
		assertNotNull(updated, "笔记本应存在");
		assertEquals(newName, updated.getName(), "编辑应生效（path id 覆盖）");
	}

	// ===================================================================
	// T_rest_11/12：旧 query string 形态 → 404（已下线，不做双轨兼容）
	// ===================================================================

	@Test
	@DisplayName("T_rest_11 旧路径 GET /api/portal/notes/content?nid= → 404（已下线）")
	void t_rest11_oldContentQueryGone() throws Exception {
		mockMvc.perform(get("/api/portal/notes/content").param("nid", nid))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("T_rest_12 旧路径 DELETE /api/agent/notes?nid= → 404（已下线）")
	void t_rest12_oldDeleteQueryGone() throws Exception {
		mockMvc.perform(delete("/api/agent/notes")
				.param("nid", nid)
				.header(ApiKeyAuthFilter.API_KEY_HEADER, plainKey))
				.andExpect(status().isNotFound());
	}

	// ===================================================================
	// T_rest_16：非规范 nid（位数/字符错）→ 404（防注入/防枚举）
	// ===================================================================

	@Test
	@DisplayName("T_rest_16 非规范 nid GET /api/portal/notes/{badNid} → 404")
	void t_rest16_badNidNotFound() throws Exception {
		// 位数错（n_ + 3 位，规范要求 10 位）
		mockMvc.perform(get("/api/portal/notes/n_BAD"))
				.andExpect(status().isNotFound());
		// 字符错（含非法字符 '-'；长度对但格式不匹配）
		mockMvc.perform(get("/api/portal/notes/n_abcdefgh-i"))
				.andExpect(status().isNotFound());
		// 规范格式但索引不存在 → 404（防枚举）
		mockMvc.perform(get("/api/portal/notes/n_ABCDEFGHIJ"))
				.andExpect(status().isNotFound());
	}
}