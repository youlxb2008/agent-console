package com.youlxb2008.admin.modules.notebook.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.AuditService;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.manager.NoteStorageManager;
import com.youlxb2008.admin.modules.note.service.NoteMetaService;
import com.youlxb2008.admin.modules.note.vo.NoteContentVO;
import com.youlxb2008.admin.modules.note.vo.NoteTreeNode;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * v3.5.1 菜单结构调整 + 门户数据展示集成测试
 * <p>
 * 覆盖：
 * 1. 菜单结构（t_permission 数据层）：
 *    「笔记本列表」3061 挂「笔记管理」3050 下（原一级目录 3060 已删除）；
 *    「审计日志」3030 迁入「日志管理」3080 下；超级管理员授权齐全。
 * 2. 门户数据展示（/api/portal/* 数据链）：
 *    notebooks 概览聚合（noteCount/lastNoteAt/agentCount）、hidden 不进入门户、
 *    选中笔记本的目录树、笔记内容（读文件 + contributors）、
 *    search/tags/by-tag/recent 查询族、审计日志数据（日志管理菜单数据源）。
 * <p>
 * 使用独立内存 H2 与临时笔记目录，不触碰开发库/真实笔记；
 * 全部 @Transactional 回滚，不删已有数据；数据在方法内构造。
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
class MenuPortalDataV351Test {

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
	private AuditService auditService;

	@Resource
	private UserPermissionService permissionService;

	@Resource
	private JdbcTemplate jdbcTemplate;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private Notebook newNotebook(String tag, Integer status) {
		String name = "nb_" + tag + "_" + SEQ.incrementAndGet();
		return notebookService.create(name, "测试笔记本", null, null, null, status, 1L);
	}

	/** D5 校验通过后再写笔记（等价 controller upsert 流程） */
	private NoteStorageManager.UpsertResult write(Agent agent, Notebook nb, String dir, String title,
			String content, String tags) {
		noteMetaService.validateNotebookWrite(agent.getId(), nb.getId());
		return noteStorageManager.upsert(agent.getId(), agent.getName(), nb.getId(), dir, title, content, tags);
	}

	private Integer c(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Integer.class, args);
	}

	/** 递归在权限树中找 id 对应的节点 */
	private UserPermission findNode(List<UserPermission> tree, Integer id) {
		for (UserPermission n : tree) {
			if (n.getId() != null && n.getId().equals(id)) {
				return n;
			}
			if (n.getChildren() != null && !n.getChildren().isEmpty()) {
				UserPermission hit = findNode(n.getChildren(), id);
				if (hit != null) {
					return hit;
				}
			}
		}
		return null;
	}

	// ============================================================
	// 场景 1：v3.5.1 菜单结构调整
	// ============================================================
	@Test
	void testMenuStructureV351() {
		log.info("========== 开始测试 v3.5.1 菜单结构调整 ==========");

		// 1) 废弃一级目录「笔记本管理」3060 已删除
		assertEquals(0, c("SELECT COUNT(*) FROM t_permission WHERE id = 3060"),
				"3060 一级目录应已删除");
		assertEquals(0, c("SELECT COUNT(*) FROM t_role_permission WHERE permission_id = 3060"),
				"3060 角色授权应已清理");

		// 2) 笔记列表 3040 挂在「笔记管理」3050 下（一级目录，route=/note）
		assertEquals(3050, c("SELECT parent_id FROM t_permission WHERE id = 3040"));
		assertEquals(2, c("SELECT level FROM t_permission WHERE id = 3040"));
		assertEquals("/note/list", jdbcTemplate.queryForObject(
				"SELECT route_path FROM t_permission WHERE id = 3040", String.class));

		// 3) 笔记本列表 3061 挂在「笔记管理」3050 下（与 3040 同一入口）
		assertEquals(3050, c("SELECT parent_id FROM t_permission WHERE id = 3061"),
				"3061 应挂在 3050 笔记管理下");
		assertEquals(2, c("SELECT level FROM t_permission WHERE id = 3061"));
		assertEquals("/notebook/list", jdbcTemplate.queryForObject(
				"SELECT route_path FROM t_permission WHERE id = 3061", String.class));
		assertEquals("system/notebook/index", jdbcTemplate.queryForObject(
				"SELECT component FROM t_permission WHERE id = 3061", String.class));

		// 4) 一级目录「日志管理」3080 存在（level=1, route=/log）
		assertEquals(1, c("SELECT COUNT(*) FROM t_permission WHERE id = 3080"));
		assertEquals(0, c("SELECT parent_id FROM t_permission WHERE id = 3080"));
		assertEquals(1, c("SELECT level FROM t_permission WHERE id = 3080"));
		assertEquals(1, c("SELECT type FROM t_permission WHERE id = 3080"));
		assertEquals("/log", jdbcTemplate.queryForObject(
				"SELECT route_path FROM t_permission WHERE id = 3080", String.class));

		// 5) 审计日志 3030 迁入「日志管理」3080 下（route=/log/audit）
		assertEquals(3080, c("SELECT parent_id FROM t_permission WHERE id = 3030"),
				"3030 审计日志应挂在日志管理 3080 下");
		assertEquals(2, c("SELECT level FROM t_permission WHERE id = 3030"));
		assertEquals("/log/audit", jdbcTemplate.queryForObject(
				"SELECT route_path FROM t_permission WHERE id = 3030", String.class));
		assertEquals("system/agent/audit", jdbcTemplate.queryForObject(
				"SELECT component FROM t_permission WHERE id = 3030", String.class));

		// 6) 超级管理员（role_id=1）授权齐全：3080 / 3061 / 3170-3175
		for (int id : new int[] { 3080, 3061, 3170, 3171, 3172, 3173, 3174, 3175 }) {
			assertEquals(1, c("SELECT COUNT(*) FROM t_role_permission WHERE role_id = 1 AND permission_id = " + id),
					"role 1 应有 权限 " + id);
		}

		// 7) 全量权限树：3080 下 children 含 3030；3050 下 children 含 3040 与 3061
		List<UserPermission> tree = permissionService.getPermissionTree();
		UserPermission logNode = findNode(tree, 3080);
		assertNotNull(logNode, "权限树应包含 3080 日志管理");
		assertTrue(logNode.getChildren().stream().anyMatch(ch -> ch.getId().equals(3030)),
				"日志管理下应包含审计日志 3030");
		UserPermission noteNode = findNode(tree, 3050);
		assertNotNull(noteNode, "权限树应包含 3050 笔记管理");
		List<Integer> childIds = noteNode.getChildren().stream().map(UserPermission::getId).toList();
		assertTrue(childIds.contains(3040) && childIds.contains(3061),
				"笔记管理下应同时包含 3040 与 3061，实际 " + childIds);
		// 3060 不应出现在树中（已被删除）
		assertTrue(findNode(tree, 3060) == null, "权限树不应包含已删除的 3060");

		log.info("========== 菜单结构调整测试执行完成 ==========");
	}

	// ============================================================
	// 场景 2：门户笔记本概览（/api/portal/notebooks 数据链）
	// ============================================================
	@Test
	void testPortalNotebooksOverview() {
		log.info("========== 开始测试门户笔记本概览聚合 ==========");
		Agent a1 = newAgent("p1");
		Agent a2 = newAgent("p2");

		// nbA：1 个 agent 关联，2 篇笔记 → noteCount=2 / agentCount=1 / lastNoteAt 非空
		Notebook nbA = newNotebook("A", 1);
		agentNotebookService.link(a1.getId(), nbA.getId(), "test-adm");
		write(a1, nbA, "", "业绩总结", "v1 md", "ai,知识库");
		write(a1, nbA, "工作日志/2026-08", "周报08", "v2 md", "ai");

		// nbB：2 个 agent 关联，1 篇笔记 → noteCount=1 / agentCount=2
		Notebook nbB = newNotebook("B", 1);
		agentNotebookService.link(a1.getId(), nbB.getId(), "test-adm");
		agentNotebookService.link(a2.getId(), nbB.getId(), "test-adm");
		write(a2, nbB, "", "方案评审", "v3 md", "运维");

		// nbH：hidden 笔记本（status=0），门户不应展示
		Notebook nbH = newNotebook("H", 0);
		agentNotebookService.link(a1.getId(), nbH.getId(), "test-adm");
		write(a1, nbH, "", "隐藏内容", "hidden md", null);

		List<NotebookVO> list = notebookService.portalNotebooks();

		NotebookVO voA = list.stream().filter(v -> v.getId().equals(nbA.getId())).findFirst().orElse(null);
		NotebookVO voB = list.stream().filter(v -> v.getId().equals(nbB.getId())).findFirst().orElse(null);
		assertNotNull(voA, "门户应包含 nbA");
		assertNotNull(voB, "门户应包含 nbB");
		assertEquals(2, voA.getNoteCount(), "nbA noteCount 应为 2");
		assertEquals(1, voA.getAgentCount(), "nbA agentCount 应为 1");
		assertNotNull(voA.getLastNoteAt(), "nbA lastNoteAt 应为非空");
		assertEquals(1, voB.getNoteCount(), "nbB noteCount 应为 1");
		assertEquals(2, voB.getAgentCount(), "nbB agentCount 应为 2");
		assertNotNull(voB.getLastNoteAt(), "nbB lastNoteAt 应为非空");

		// hidden 笔记本不出现在门户
		assertTrue(list.stream().noneMatch(v -> v.getId().equals(nbH.getId())),
				"status=0（hidden）笔记本不应出现在门户");
		log.info("门户笔记本数 = {}（不含 hidden nbH）", list.size());
		log.info("========== 门户笔记本概览测试执行完成 ==========");
	}

	// ============================================================
	// 场景 3：选中笔记本后的目录树 + 笔记内容（门户展示链）
	// ============================================================
	@Test
	void testPortalNotebookTreeAndContent() {
		log.info("========== 开始测试门户笔记本目录树与内容 ==========");
		Agent agent = newAgent("t");
		Notebook nb = newNotebook("tree", 1);
		agentNotebookService.link(agent.getId(), nb.getId(), "test-adm");

		// 根目录 1 篇 + 嵌套目录 2 篇
		NoteStorageManager.UpsertResult root = write(agent, nb, "", "根笔记", "# root", null);
		write(agent, nb, "工作日志/2026-08", "日报 0829", "day 1", "日报");
		NoteStorageManager.UpsertResult nested = write(agent, nb, "工作日志/2026-08", "日报 0830",
				"day 2", "日报");

		// 等价门户接口 /api/portal/notebooks/{id}/notes/tree 的数据链
		List<NoteMeta> metas = noteMetaService.listByNotebook(nb.getId());
		noteMetaService.fillNotebookNames(metas);
		List<NoteTreeNode> tree = noteMetaService.buildTree(metas);

		// 根：folder「工作日志」+ note「根笔记」
		assertEquals(2, tree.size(), "根层应为 1 folder + 1 note");
		NoteTreeNode folder = tree.stream().filter(n -> "folder".equals(n.getType())).findFirst().orElse(null);
		NoteTreeNode rootNote = tree.stream().filter(n -> "note".equals(n.getType())).findFirst().orElse(null);
		assertNotNull(folder, "应有 工作日志 folder");
		assertEquals("工作日志", folder.getName());
		assertNotNull(rootNote, "应有根目录笔记");
		assertEquals(root.getNid(), rootNote.getNid());
		assertNotNull(rootNote.getNotebookId());
		assertNotNull(rootNote.getNotebookName(), "树节点应回填 notebookName");

		// folder 内含子 folder 2026-08 与两篇 note
		NoteTreeNode sub = folder.getChildren().stream()
				.filter(n -> "folder".equals(n.getType())).findFirst().orElse(null);
		assertNotNull(sub, "工作日志下应有 2026-08 子目录");
		assertEquals("2026-08", sub.getName());
		assertEquals(2, sub.getChildren().size(), "2026-08 下应有 2 篇笔记");

		// 笔记内容：读文件正文 + contributors
		NoteContentVO vo = noteStorageManager.readContent(nested.getNid());
		assertEquals("day 2", vo.getContentMd(), "正文应来自文件真相源");
		assertEquals("日报 0830", vo.getTitle());
		assertNotNull(vo.getContributors(), "contributors 应非空");
		assertFalse(vo.getContributors().isEmpty(), "创建者关联后应有贡献者");
		log.info("========== 门户目录树与内容测试执行完成 ==========");
	}

	// ============================================================
	// 场景 4：门户查询族（search / tags / by-tag / recent，按 notebookId 隔离）
	// ============================================================
	@Test
	void testPortalQueryFamily() {
		log.info("========== 开始测试门户查询族 ==========");
		Agent agent = newAgent("q");
		Notebook nbA = newNotebook("QA", 1);
		Notebook nbB = newNotebook("QB", 1);
		agentNotebookService.link(agent.getId(), nbA.getId(), "test-adm");
		agentNotebookService.link(agent.getId(), nbB.getId(), "test-adm");

		write(agent, nbA, "", "业绩总结报告", "a1", "ai,知识库");
		write(agent, nbA, "", "运营活动策划", "a2", "ai");
		write(agent, nbA, "", "系统运维手册", "a3", "运维");
		write(agent, nbA, "", "大模型接入说明", "a4", "aigc");
		write(agent, nbB, "", "业绩与预算对齐", "b1", "ai");

		// search：nbA 内标题包含「业绩」仅 1 篇；nbB 的「业绩」不干扰
		List<NoteMeta> hit = noteMetaService.searchByKeyword(nbA.getId(), "业绩", 20);
		assertEquals(1, hit.size());
		assertEquals("业绩总结报告", hit.get(0).getTitle());

		// tags：nbA 聚合 ai=2 / 知识库=1 / 运维=1 / aigc=1，count 降序
		var tags = noteMetaService.aggregateTags(nbA.getId());
		assertEquals(4, tags.size());
		assertEquals("ai", tags.get(0).getTag());
		assertEquals(2L, tags.get(0).getCount());

		// by-tag：精确匹配，nbA 内 tag=ai 2 篇；tag=ai 不应命中 aigc 笔记
		List<NoteMeta> ai = noteMetaService.listByTag(nbA.getId(), "ai", 20);
		assertEquals(2, ai.size(), "nbA 内 ai 应精确命中 2 篇（不含 aigc）");
		// 跨笔记本隔离：nbB 的 ai 笔记不影响 nbA 结果
		assertEquals(0, noteMetaService.listByTag(nbB.getId(), "知识库", 20).size());

		// recent：nbA 全部 4 篇，update_time 降序（同秒按 id 降序）
		List<NoteMeta> recent = noteMetaService.listRecent(nbA.getId(), 10);
		assertEquals(4, recent.size());
		assertEquals(4, noteMetaService.listByNotebook(nbA.getId()).size());
		log.info("========== 门户查询族测试执行完成 ==========");
	}

	// ============================================================
	// 场景 5：审计日志数据（「日志管理」菜单下展示的数据源）
	// ============================================================
	@Test
	void testAuditLogDisplayData() {
		log.info("========== 开始测试审计日志数据 ==========");
		// 造审计：notebook.create / note.write / notebook.delete(denied)
		auditService.append("admin", "notebook.create", "nb/1", null, "success", "127.0.0.1");
		auditService.append("admin", "note.write", "n_abc", null, "success", "127.0.0.1");
		auditService.append("admin", "notebook.delete", "nb/2", null, "denied", "127.0.0.1");
		auditService.append("ag_audit_1", "note.write", "n_def", null, "success", "10.0.0.1");
		auditService.append("ag_audit_1", "note.write", "n_ghi", null, "success", "10.0.0.1");

		// 全量分页（audit:list 数据源）
		var all = auditService.pageQuery(1, 10, null, null, null);
		assertTrue(all.getTotal() >= 5, "应能查到全部审计记录，total=" + all.getTotal());

		// action 筛选
		var writes = auditService.pageQuery(1, 10, null, "note.write", null);
		assertEquals(3, writes.getTotal(), "note.write 应为 3 条");

		// principal 筛选（agent 维度）
		var admin = auditService.pageQuery(1, 10, "admin", null, null);
		assertEquals(3, admin.getTotal(), "admin 应为 3 条");

		// result 筛选
		var denied = auditService.pageQuery(1, 10, null, "notebook.delete", "denied");
		assertEquals(1, denied.getTotal(), "denied 应查得到");

		// 时间范围（CreateTime 精确到秒，用宽范围验证）
		var byTime = auditService.pageQuery(1, 10, null, null, null,
				new Date(System.currentTimeMillis() - 60_000L), new Date(System.currentTimeMillis() + 60_000L));
		assertTrue(byTime.getTotal() >= 5, "时间范围应命中全部记录");
		log.info("========== 审计日志数据测试执行完成 ==========");
	}
}