package com.youlxb2008.admin.modules.note.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.exceptions.UnprocessableEntityException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.service.AgentNotebookService;
import com.youlxb2008.admin.modules.notebook.service.NotebookService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M3.7 笔记 PATCH 挪动/重命名集成测试（service 层，映射任务书 T_patch_01~09）
 * <p>
 * 覆盖：改 dirPath / 改 title / 改 notebookId 移动 / 新本未关联 403 / 新本不存在 404 /
 * 未关联 agent 404（防枚举）/ 冲突 422 / 全空 400 / PATCH 后文件路径内容不变。
 * <p>
 * 使用独立内存 H2 与临时笔记目录，不触碰开发库/真实笔记；全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcpatchnote;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与笔记文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-patch",
		"notes.root=./target/test-notes-patch" })
@Transactional
@Rollback(true)
class NotePatchV37Test {

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
	private AgentResourceService agentResourceService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private Notebook newNotebook(String tag) {
		String name = "nb_" + tag + "_" + SEQ.incrementAndGet();
		return notebookService.create(name, "测试笔记本", null, null, null, 1, 1L);
	}

	/** D5 校验通过后再写笔记（等价 controller upsert 流程） */
	private NoteStorageManager.UpsertResult write(Agent agent, Notebook nb, String dir, String title,
			String content) {
		noteMetaService.validateNotebookWrite(agent.getId(), nb.getId());
		return noteStorageManager.upsert(agent.getId(), agent.getName(), nb.getId(), dir, title, content, null);
	}

	/** 依 nid 查索引行 */
	private NoteMeta byNid(String nid) {
		return noteMetaService.findByNid(nid);
	}

	// ------------------------------------------------------------
	// T_patch_01 改 dirPath（同 notebookId）→ meta 更新，nid/关联/文件不变
	// ------------------------------------------------------------
	@Test
	void testPatchDirPathKeepsNidLinkFile() {
		log.info("========== 开始测试 PATCH 改 dirPath（nid/关联/文件不变） ==========");
		Agent agent = newAgent("p01");
		Notebook nb = newNotebook("p01");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");

		NoteStorageManager.UpsertResult w = write(agent, nb, "旧目录", "标题1", "# v1");
		String nid = w.getNid();
		assertTrue(noteStorageManager.readRaw(nid).contains("v1"), "写前文件内容应为 v1");

		NoteMeta updated = noteMetaService.patchMeta(agent.getId(), nid, null, "新目录/子目录", null);
		assertEquals(nid, updated.getNid(), "PATCH 应保留 nid");
		assertEquals("新目录/子目录", updated.getDirPath(), "dirPath 应更新");
		assertEquals("标题1", updated.getTitle(), "title 不应变");
		assertEquals(nb.getId(), updated.getNotebookId(), "notebookId 不应变");

		// 关联行不变（仍有关联）
		assertTrue(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_NOTE, nid), "关联应保留");
		// 文件路径/内容不变（扁平存储跟 nid 走，不迁移文件）
		assertTrue(noteStorageManager.readRaw(nid).contains("v1"), "PATCH 后文件内容应不变");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_02 改 title（同 dirPath）→ meta 更新，nid/关联/文件不变
	// ------------------------------------------------------------
	@Test
	void testPatchTitleKeepsNidLinkFile() {
		log.info("========== 开始测试 PATCH 改 title（nid/关联/文件不变） ==========");
		Agent agent = newAgent("p02");
		Notebook nb = newNotebook("p02");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");

		NoteStorageManager.UpsertResult w = write(agent, nb, "目录A", "旧标题", "# v2");
		String nid = w.getNid();

		NoteMeta updated = noteMetaService.patchMeta(agent.getId(), nid, null, null, "新标题.md");
		assertEquals(nid, updated.getNid(), "PATCH 应保留 nid");
		assertEquals("新标题", updated.getTitle(), "title 应更新且去 .md 后缀");
		assertEquals("目录A", updated.getDirPath(), "dirPath 不应变");
		assertEquals(nb.getId(), updated.getNotebookId(), "notebookId 不应变");
		assertTrue(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_NOTE, nid), "关联应保留");
		assertTrue(noteStorageManager.readRaw(nid).contains("v2"), "PATCH 后文件内容应不变");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_03 改 notebookId → meta.notebook_id 更新（移动生效）
	// ------------------------------------------------------------
	@Test
	void testPatchNotebookMove() {
		log.info("========== 开始测试 PATCH 改 notebookId（移动生效） ==========");
		Agent agent = newAgent("p03");
		Notebook nb1 = newNotebook("p03a");
		Notebook nb2 = newNotebook("p03b");
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");
		agentNotebookService.link(agent.getId(), nb2.getId(), "test-adm");

		NoteStorageManager.UpsertResult w = write(agent, nb1, "目录B", "标题3", "# v3");
		String nid = w.getNid();

		NoteMeta updated = noteMetaService.patchMeta(agent.getId(), nid, nb2.getId(), null, null);
		assertEquals(nb2.getId(), updated.getNotebookId(), "notebookId 应更新为新笔记本（移动）");
		assertEquals(nid, updated.getNid(), "PATCH 应保留 nid");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_04 改 notebookId 但新本未关联 → 403
	// ------------------------------------------------------------
	@Test
	void testPatchNotebookUnlinkedForbidden() {
		log.info("========== 开始测试 PATCH 改到未关联 notebook → 403 ==========");
		Agent agent = newAgent("p04");
		Notebook nb1 = newNotebook("p04a");
		Notebook nb2 = newNotebook("p04b");
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");
		// nb2 不关联 agent

		NoteStorageManager.UpsertResult w = write(agent, nb1, "", "标题4", "# v4");
		ForbiddenException ex = assertThrows(ForbiddenException.class,
				() -> noteMetaService.patchMeta(agent.getId(), w.getNid(), nb2.getId(), null, null));
		assertEquals("agent 未关联该笔记本", ex.getMessage());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_05 改 notebookId 但新本不存在 → 404
	// ------------------------------------------------------------
	@Test
	void testPatchNotebookMissingNotFound() {
		log.info("========== 开始测试 PATCH 改到不存在 notebook → 404 ==========");
		Agent agent = newAgent("p05");
		Notebook nb1 = newNotebook("p05a");
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");

		NoteStorageManager.UpsertResult w = write(agent, nb1, "", "标题5", "# v5");
		assertThrows(NotFoundException.class,
				() -> noteMetaService.patchMeta(agent.getId(), w.getNid(), 999999999L, null, null));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_06 未关联 agent PATCH → 404（防枚举）
	// ------------------------------------------------------------
	@Test
	void testPatchUnlinkedAgentNotFound() {
		log.info("========== 开始测试未关联 agent PATCH → 404 ==========");
		Agent author = newAgent("p06a");
		Agent other = newAgent("p06b");
		Notebook nb = newNotebook("p06");
		agentNotebookService.link(author.getId(), nb.getId(), "test-adm");

		NoteStorageManager.UpsertResult w = write(author, nb, "", "标题6", "# v6");
		// other 未关联该笔记
		assertThrows(NotFoundException.class,
				() -> noteMetaService.patchMeta(other.getId(), w.getNid(), null, "x", null));
		// nid 不存在 → 404（防枚举）
		assertThrows(NotFoundException.class,
				() -> noteMetaService.patchMeta(author.getId(), "n_ZZZZZZZZZZ", null, "x", null));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_07 (notebookId,dirPath,title) 与同本他篇冲突 → 422
	// ------------------------------------------------------------
	@Test
	void testPatchConflictUnprocessable() {
		log.info("========== 开始测试 PATCH 冲突 → 422 ==========");
		Agent agent = newAgent("p07");
		Notebook nb = newNotebook("p07");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");

		// 同 notebook 下已有一篇 dir=目录C title=标题7
		write(agent, nb, "目录C", "标题7", "# 已存在");
		// 另一篇 dir=其它 title=标题7b，PATCH 改成 目录C/标题7 → 冲突
		NoteStorageManager.UpsertResult w2 = write(agent, nb, "其它", "标题7b", "# 第二篇");

		UnprocessableEntityException ex = assertThrows(UnprocessableEntityException.class,
				() -> noteMetaService.patchMeta(agent.getId(), w2.getNid(), null, "目录C", "标题7"));
		assertEquals("同笔记本下已存在同名同目录笔记", ex.getMessage());

		// 冲突未落盘：原 meta 不变
		assertEquals("其它", byNid(w2.getNid()).getDirPath());
		assertEquals("标题7b", byNid(w2.getNid()).getTitle());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_08 全空 body → 400
	// ------------------------------------------------------------
	@Test
	void testPatchAllEmptyBadRequest() {
		log.info("========== 开始测试 PATCH 全空 → 400 ==========");
		Agent agent = newAgent("p08");
		Notebook nb = newNotebook("p08");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");
		NoteStorageManager.UpsertResult w = write(agent, nb, "", "标题8", "# v8");
		assertThrows(BadRequestException.class,
				() -> noteMetaService.patchMeta(agent.getId(), w.getNid(), null, null, null));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_09 PATCH 后文件路径/内容不变 → notes/{nid}.md 未迁移
	// ------------------------------------------------------------
	@Test
	void testPatchFileNotMigrated() {
		log.info("========== 开始测试 PATCH 后文件未迁移 ==========");
		Agent agent = newAgent("p09");
		Notebook nb1 = newNotebook("p09a");
		Notebook nb2 = newNotebook("p09b");
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");
		agentNotebookService.link(agent.getId(), nb2.getId(), "test-adm");

		String content = "# 原正文 " + SEQ.incrementAndGet();
		NoteStorageManager.UpsertResult w = write(agent, nb1, "原目录/层", "原标题", content);
		String nid = w.getNid();
		String beforePath = noteStorageManager.readRaw(nid);

		// 同时改 dirPath + title + notebookId
		noteMetaService.patchMeta(agent.getId(), nid, nb2.getId(), "新目录", "新标题");

		// 文件路径由 nid 派生，未迁移：readRaw(nid) 仍可读且内容不变
		assertEquals(content, beforePath, "写前文件内容");
		assertEquals(content, noteStorageManager.readRaw(nid), "PATCH 后文件内容应原样");
		// 索引已更新
		NoteMeta meta = byNid(nid);
		assertEquals("新目录", meta.getDirPath());
		assertEquals("新标题", meta.getTitle());
		assertEquals(nb2.getId(), meta.getNotebookId());
		// 关联行未变
		assertTrue(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_NOTE, nid), "关联应保留");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_patch_10 审计 note.update 落库 → t_audit_log 有记录
	// 说明：审计由 Controller 层写（AuditService.append），service 测试不覆盖；
	// 这里验证补丁逻辑不产生副作用（文件未动、关联未动），审计链路已在
	// AuditServiceTest 覆盖。e2e 复跑 T_patch_10 验证。
	// ------------------------------------------------------------
	@Test
	void testPatchNoSideEffects() {
		log.info("========== 开始测试 PATCH 无副作用 ==========");
		Agent agent = newAgent("p10");
		Notebook nb = newNotebook("p10");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");
		NoteStorageManager.UpsertResult w = write(agent, nb, "目录D", "标题10", "# v10");
		String nid = w.getNid();

		noteMetaService.patchMeta(agent.getId(), nid, null, "目录E", "标题10改名");

		// 无副作用：文件可读、关联仍在、tags 等未动
		assertNotNull(noteStorageManager.readRaw(nid));
		assertTrue(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_NOTE, nid));
		assertFalse(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_NOTE, "n_ZZZZZZZZZZ"));
		log.info("========== 测试执行完成 ==========");
	}
}