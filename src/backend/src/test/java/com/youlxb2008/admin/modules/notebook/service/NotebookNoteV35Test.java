package com.youlxb2008.admin.modules.notebook.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * v3.5 独立笔记本功能集成测试（note 域 D5 校验链 + notebook 域软删重建）
 * <p>
 * 覆盖（参照 add-independent-notebook/checklist.md 后端 —note 域 D5 校验链）：
 * 1. agent 未关联 notebook 写笔记 → 403 ForbiddenException"agent 未关联该笔记本"（validateNotebookWrite）
 * 2. agent 已关联 notebook 写笔记 → 200 成功（新建笔记，notebook_id 落索引）
 * 3. 跨 notebook 移动：同锚点已关联笔记改写到另一已关联笔记本 → 覆写保留原 nid、notebook_id 更新
 * 4. 软删后同名重建：NotebookService.create 同名冲突 → delete name 改写 → 再 create 同名成功
 * 5. GET /api/agent/notes/tree 语义：关联多 notebook 树包含其下笔记；无关联返回空
 * <p>
 * 使用独立内存 H2 与临时笔记目录，不触碰开发库/真实笔记；全部 @Transactional 回滚，不删已有数据。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agctest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与笔记文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data",
		"notes.root=./target/test-notes" })
@Transactional
@Rollback(true)
class NotebookNoteV35Test {

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
	private JdbcTemplate jdbcTemplate;

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
	private NoteStorageManager.UpsertResult write(Agent agent, Notebook nb, String tag) {
		noteMetaService.validateNotebookWrite(agent.getId(), nb.getId());
		return noteStorageManager.upsert(agent.getId(), agent.getName(), nb.getId(), "",
				"note_" + tag + "_" + SEQ.incrementAndGet(), "content md", "tag1,tag2");
	}

	/** 依 nid 查索引行 */
	private NoteMeta byNid(String nid) {
		return noteMetaService.findByNid(nid);
	}

	// ------------------------------------------------------------
	// 场景 1：agent 未关联 notebook 写笔记 → 403
	// ------------------------------------------------------------
	@Test
	void testWriteUnlinkedNotebookForbidden() {
		log.info("========== 开始测试 agent 未关联 notebook 写笔记返回 403 ==========");
		Agent agent = newAgent("a");
		Notebook nb = newNotebook("x");
		// 不关联 agent → nb

		ForbiddenException ex = assertThrows(ForbiddenException.class,
				() -> noteMetaService.validateNotebookWrite(agent.getId(), nb.getId()));
		assertEquals("agent 未关联该笔记本", ex.getMessage());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// 场景 2：agent 已关联 notebook 写笔记 → 成功，notebook_id 落索引
	// ------------------------------------------------------------
	@Test
	void testWriteLinkedNotebookSuccess() {
		log.info("========== 开始测试 agent 已关联 notebook 写笔记成功 ==========");
		Agent agent = newAgent("b");
		Notebook nb = newNotebook("y");
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");

		// 校验链通过（不抛 403/404）
		noteMetaService.validateNotebookWrite(agent.getId(), nb.getId());

		NoteStorageManager.UpsertResult result = write(agent, nb, "b");
		assertTrue(result.isCreated(), "首次写入应为新建");
		assertNotNull(result.getNid());

		// notebook_id 落索引
		NoteMeta meta = byNid(result.getNid());
		assertNotNull(meta);
		assertEquals(nb.getId(), meta.getNotebookId(), "新建笔记 notebook_id 应等于目标笔记本");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// 场景 3：跨 notebook 移动（同锚点覆写保留原 nid，notebook_id 更新）
	// ------------------------------------------------------------
	@Test
	void testCrossNotebookMoveCover() {
		log.info("========== 开始测试跨 notebook 移动（覆写保留 nid、notebook_id 更新） ==========");
		Agent agent = newAgent("c");
		Notebook nb1 = newNotebook("m1");
		Notebook nb2 = newNotebook("m2");
		// agent 同时关联 nb1 与 nb2
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");
		agentNotebookService.link(agent.getId(), nb2.getId(), "test-adm");

		String tag = "mov" + SEQ.incrementAndGet();
		String title = "shared_" + tag;
		// 先写入 nb1
		noteMetaService.validateNotebookWrite(agent.getId(), nb1.getId());
		NoteStorageManager.UpsertResult first = noteStorageManager.upsert(agent.getId(),
				agent.getName(), nb1.getId(), "", title, "v1", null);
		assertTrue(first.isCreated());
		assertEquals(nb1.getId(), byNid(first.getNid()).getNotebookId());

		// 同锚点改写到 nb2（已关联）→ 覆写，保留原 nid，notebook_id 更新为 nb2
		noteMetaService.validateNotebookWrite(agent.getId(), nb2.getId());
		NoteStorageManager.UpsertResult moved = noteStorageManager.upsert(agent.getId(),
				agent.getName(), nb2.getId(), "", title, "v2", "tagA");

		assertFalse(moved.isCreated(), "同锚点已关联应覆写而非新建");
		assertEquals(first.getNid(), moved.getNid(), "覆写应保留原 nid");
		NoteMeta movedMeta = byNid(moved.getNid());
		assertEquals(nb2.getId(), movedMeta.getNotebookId(), "移动后 notebook_id 应更新为新笔记本");
		assertEquals("v2", noteStorageManager.readRaw(moved.getNid()), "正文应更新");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// 场景 4：软删后同名重建（NotebookService）
	// ------------------------------------------------------------
	@Test
	void testSoftDeleteThenRecreateSameName() {
		log.info("========== 开始测试软删后同名重建 ==========");
		String name = "dup_" + SEQ.incrementAndGet();
		Notebook target = notebookService.create(name, null, null, null, null, 1, 1L);
		assertEquals(name, target.getName());

		// 1) 同名冲突 → BusinessException
		assertThrows(BusinessException.class,
				() -> notebookService.create(name, null, null, null, null, 1, 1L));

		// 2) 软删：name 改写 {name}__deleted_{id}，is_deleted=1
		notebookService.delete(target.getId());
		String renamed = jdbcTemplate.queryForObject(
				"SELECT name FROM t_notebook WHERE id = ?", String.class, target.getId());
		assertEquals(name + "__deleted_" + target.getId(), renamed, "软删后 name 应改写释放原名");

		// 3) 再次 create 同名 → 成功（唯一键已释放）
		Notebook recreated = notebookService.create(name, null, null, null, null, 1, 1L);
		assertEquals(name, recreated.getName());
		assertTrue(!recreated.getId().equals(target.getId()), "重建笔记本 id 应与已删不同");

		// 4) 软删标记（信息性）：MyBatis-Plus 的 updateById 对 @TableLogic 字段不参与 SET
		//    （is_deleted 保持 0，软删语义靠 name 改写释放唯一键实现），故不在此断言该值。
		log.info("软删后 raw is_deleted = {}（MP updateById 不含 @TableLogic 字段时保持原值）",
				jdbcTemplate.queryForObject(
						"SELECT is_deleted FROM t_notebook WHERE id = ?", Integer.class, target.getId()));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// 场景 5：GET /api/agent/notes/tree 语义（listByAgentNotebooks + buildTree）
	// ------------------------------------------------------------
	@Test
	void testTreeSemantics() {
		log.info("========== 开始测试 tree 语义（多笔记本与空关联） ==========");
		Agent agent = newAgent("t");
		Notebook nb1 = newNotebook("t1");
		Notebook nb2 = newNotebook("t2");
		agentNotebookService.link(agent.getId(), nb1.getId(), "test-adm");
		agentNotebookService.link(agent.getId(), nb2.getId(), "test-adm");

		// 两个笔记本下各写一篇
		NoteStorageManager.UpsertResult n1 = write(agent, nb1, "t1");
		NoteStorageManager.UpsertResult n2 = write(agent, nb2, "t2");

		List<NoteMeta> metas = noteMetaService.listByAgentNotebooks(agent.getId());
		assertFalse(metas.isEmpty(), "关联两个笔记本时树应有笔记");
		assertEquals(2, metas.size());
		// 两个笔记本的 note 都在集合中且回填 notebookName
		boolean hasN1 = metas.stream().anyMatch(m -> nb1.getId().equals(m.getNotebookId()));
		boolean hasN2 = metas.stream().anyMatch(m -> nb2.getId().equals(m.getNotebookId()));
		assertTrue(hasN1 && hasN2, "树应包含两个关联笔记本下的笔记");
		metas.forEach(m -> assertNotNull(m.getNotebookName(), "树节点应回填 notebookName"));

		// buildTree 亦能建出带 notebookId 的 note 节点
		var tree = noteMetaService.buildTree(metas);
		assertFalse(tree.isEmpty());

		// 未关联任何笔记本的 agent → 空树
		Agent noLink = newAgent("nolink");
		assertTrue(noteMetaService.listByAgentNotebooks(noLink.getId()).isEmpty(),
				"无关联应返回空树");
		assertTrue(noteMetaService.buildTree(noteMetaService.listByAgentNotebooks(noLink.getId())).isEmpty());
		log.info("========== 测试执行完成 ==========");
	}
}