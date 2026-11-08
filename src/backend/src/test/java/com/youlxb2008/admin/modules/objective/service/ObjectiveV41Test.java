package com.youlxb2008.admin.modules.objective.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.service.AgentResourceService;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.objective.dto.ObjectiveCreateReq;
import com.youlxb2008.admin.modules.objective.dto.ObjectivePatchReq;
import com.youlxb2008.admin.modules.objective.entity.Objective;
import com.youlxb2008.admin.modules.objective.manager.ObjectiveStorageManager;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveDetailVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveVO;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M4.1 目标模块集成测试（service 层，映射任务书 T_obj_01~11/14）
 * <p>
 * 覆盖：建目标落 md / progress 默认 0 / 子项解析算进度 / 索引更新 oid 不变 /
 * 非本人 404（防枚举）/ 空 body 400 / 管理端分页 / 按 agent 查 / 门户只读 / 软删 / 非规范 oid。
 * <p>
 * 使用独立内存 H2 与临时目标目录，不触碰开发库/真实数据；全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcobj;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与目标文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-objective",
		"objectives.root=./target/test-objectives" })
@Transactional
@Rollback(true)
class ObjectiveV41Test {

	@Resource
	private AgentService agentService;

	@Resource
	private AgentResourceService agentResourceService;

	@Resource
	private ObjectiveService objectiveService;

	@Resource
	private ObjectiveStorageManager objectiveStorageManager;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private ObjectiveCreateReq req(String title) {
		ObjectiveCreateReq r = new ObjectiveCreateReq();
		r.setTitle(title);
		r.setPeriod("2026-Q4");
		r.setTargetDate(Date.valueOf("2026-12-31"));
		r.setDescMd("达成某某");
		return r;
	}

	// ------------------------------------------------------------
	// T_obj_01 建目标 → oid 返回，md 落盘 OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md
	// ------------------------------------------------------------
	@Test
	void testCreateWritesMd() {
		log.info("========== 开始测试 T_obj_01 建目标落 md ==========");
		Agent agent = newAgent("o01");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("年度规划"));
		assertNotNull(obj.getOid(), "应生成 oid");
		assertTrue(obj.getOid().matches("^o_[0-9A-Za-z]{10}$"), "oid 应为 o_+10位base62");
		assertEquals("agent-" + agent.getId() + "/objective-" + obj.getOid() + ".md", obj.getBodyPath());
		// md 已落盘（truth source 可读）
		String md = objectiveStorageManager.readRaw(obj.getBodyPath());
		assertTrue(md.contains("## 描述"), "md 应含描述段");
		assertTrue(md.contains("## 子项"), "md 应含子项段");
		// 作者关联行已写（D6 范式）
		assertTrue(agentResourceService.hasLink(agent.getId(), AgentResource.TYPE_OBJECTIVE, obj.getOid()));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_02 建目标默认 progress=0，md 含「子项」空模板
	// ------------------------------------------------------------
	@Test
	void testCreateDefaultProgressZero() {
		log.info("========== 开始测试 T_obj_02 默认 progress=0 ==========");
		Agent agent = newAgent("o02");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("默认进度"));
		assertEquals(0, obj.getProgress().compareTo(BigDecimal.ZERO), "新建目标 progress 应为 0");
		Objective row = objectiveService.findByOid(obj.getOid());
		assertEquals(0, row.getProgress().compareTo(BigDecimal.ZERO), "索引 progress 应为 0");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_03 agent 改 md（2 个 - [x] 2 个 - [ ]）→ PATCH 后 progress=50
	// ------------------------------------------------------------
	@Test
	void testPatchRecalculatesProgress() {
		log.info("========== 开始测试 T_obj_03 子项解析算进度 ==========");
		Agent agent = newAgent("o03");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("带子项目标"));
		// 模拟 agent 直接往 md「子项」段追加 4 个 checkbox（2 done 2 todo）
		String md = objectiveStorageManager.readRaw(obj.getBodyPath());
		String withSubs = md.replace("## 子项\n", "## 子项\n- [x] 完成 A\n- [x] 完成 B\n- [ ] 待做 C\n- [ ] 待做 D\n");
		objectiveStorageManagerTestWrite(obj.getBodyPath(), withSubs);

		// PATCH 描述段（descMd 覆写）→ replaceDesc 保留子项段 → 重算 progress = 2/4 = 50
		ObjectivePatchReq patch = new ObjectivePatchReq();
		patch.setDescMd("更新后的描述");
		Objective updated = objectiveService.patchMeta(agent.getId(), obj.getOid(), patch);
		assertEquals(50.00d, updated.getProgress().doubleValue(), 0.001, "2/4 应等于 50");
		Objective row = objectiveService.findByOid(obj.getOid());
		assertEquals(50.00d, row.getProgress().doubleValue(), 0.001, "索引 progress 应刷新为 50");
		// 子项段保留（描述覆写不丢 checkbox）
		String after = objectiveStorageManager.readRaw(obj.getBodyPath());
		assertTrue(after.contains("- [x] 完成 A") && after.contains("- [ ] 待做 D"), "子项段应保留");
		log.info("========== 测试执行完成 ==========");
	}

	/** 测试辅助：直接覆写 md 文件（模拟 agent 直接改文件系统） */
	private void objectiveStorageManagerTestWrite(String bodyPath, String content) {
		java.nio.file.Path base = java.nio.file.Paths.get("./target/test-objectives").toAbsolutePath().normalize();
		java.nio.file.Path target = base.resolve(bodyPath).normalize();
		try {
			java.nio.file.Files.createDirectories(target.getParent());
			java.nio.file.Files.writeString(target, content, java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
	}

	// ------------------------------------------------------------
	// T_obj_04 改 title/period/targetDate/status → 索引更新，oid 不变
	// ------------------------------------------------------------
	@Test
	void testPatchIndexUpdateKeepsOid() {
		log.info("========== 开始测试 T_obj_04 索引更新 oid 不变 ==========");
		Agent agent = newAgent("o04");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("原标题"));
		ObjectivePatchReq patch = new ObjectivePatchReq();
		patch.setTitle("新标题");
		patch.setPeriod("2026");
		patch.setTargetDate(Date.valueOf("2026-06-30"));
		patch.setStatus(1);
		Objective updated = objectiveService.patchMeta(agent.getId(), obj.getOid(), patch);
		assertEquals(obj.getOid(), updated.getOid(), "oid 应不变");
		assertEquals("新标题", updated.getTitle());
		assertEquals("2026", updated.getPeriod());
		assertEquals(Date.valueOf("2026-06-30"), updated.getTargetDate());
		assertEquals(1, updated.getStatus());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_05 非本人 PATCH/DELETE → 404（防枚举）
	// ------------------------------------------------------------
	@Test
	void testOthersPatchDeleteNotFound() {
		log.info("========== 开始测试 T_obj_05 非本人 404 ==========");
		Agent owner = newAgent("o05a");
		Agent other = newAgent("o05b");
		Objective obj = objectiveService.create(owner.getId(), owner.getName(), req("私域目标"));
		ObjectivePatchReq patch = new ObjectivePatchReq();
		patch.setTitle("想篡改");
		assertThrows(NotFoundException.class,
				() -> objectiveService.patchMeta(other.getId(), obj.getOid(), patch));
		assertThrows(NotFoundException.class,
				() -> objectiveService.delete(other.getId(), obj.getOid()));
		// 目标仍在（未被篡改/删除）
		assertNotNull(objectiveService.findByOid(obj.getOid()));
		// 不存在的 oid 同样 404（防枚举）
		assertThrows(NotFoundException.class,
				() -> objectiveService.patchMeta(owner.getId(), "o_ZZZZZZZZZZ", patch));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_06 空 body PATCH → 400
	// ------------------------------------------------------------
	@Test
	void testPatchAllEmptyBadRequest() {
		log.info("========== 开始测试 T_obj_06 空 body 400 ==========");
		Agent agent = newAgent("o06");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("空 body"));
		assertThrows(BadRequestException.class,
				() -> objectiveService.patchMeta(agent.getId(), obj.getOid(), new ObjectivePatchReq()));
		// title 必填（create 层校验）
		ObjectiveCreateReq empty = new ObjectiveCreateReq();
		empty.setTitle("   ");
		assertThrows(BadRequestException.class,
				() -> objectiveService.create(agent.getId(), agent.getName(), empty));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_07 管理端分页（keyword/agentId 筛选）→ 正确
	// ------------------------------------------------------------
	@Test
	void testAdminPageSearch() {
		log.info("========== 开始测试 T_obj_07 管理端分页 ==========");
		Agent agent = newAgent("o07");
		objectiveService.create(agent.getId(), agent.getName(), req("巡检规划"));
		objectiveService.create(agent.getId(), agent.getName(), req("写作计划"));
		// keyword 筛选
		var page = objectiveService.pageSearch(1, 10, null, "巡检");
		assertEquals(1, page.getTotal(), "keyword 应命中 1 条");
		// agentId 筛选
		var page2 = objectiveService.pageSearch(1, 10, agent.getId(), null);
		assertEquals(2, page2.getTotal(), "该 agent 应有 2 条");
		// 组合
		var page3 = objectiveService.pageSearch(1, 10, agent.getId(), "计划");
		assertEquals(1, page3.getTotal(), "组合筛选应命中 1 条");
		// 无 keyword 全量
		var page4 = objectiveService.pageSearch(1, 10, null, null);
		assertTrue(page4.getTotal() >= 2, "全量应 ≥2 条");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_08 管理端按 agent 查 → listByAgent 正确
	// ------------------------------------------------------------
	@Test
	void testAdminListByAgent() {
		log.info("========== 开始测试 T_obj_08 按 agent 查 ==========");
		Agent agent = newAgent("o08");
		objectiveService.create(agent.getId(), agent.getName(), req("目标一"));
		objectiveService.create(agent.getId(), agent.getName(), req("目标二"));
		List<ObjectiveVO> list = objectiveService.listByAgent(agent.getId());
		assertEquals(2, list.size(), "该 agent 应有 2 个目标");
		assertEquals(agent.getId(), list.get(0).getAgentId(), "应回填 agentId");
		assertNotNull(list.get(0).getAgentName(), "应回填 agentName");
		// agent 不存在 → 404
		assertThrows(NotFoundException.class, () -> objectiveService.listByAgent(999999999L));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_09 门户 /agents/{agentId}/objectives → 只读列表
	// ------------------------------------------------------------
	@Test
	void testPortalListByAgent() {
		log.info("========== 开始测试 T_obj_09 门户只读列表 ==========");
		Agent agent = newAgent("o09");
		objectiveService.create(agent.getId(), agent.getName(), req("门户目标"));
		List<ObjectiveVO> list = objectiveService.portalListByAgent(agent.getId());
		assertEquals(1, list.size());
		assertEquals("门户目标", list.get(0).getTitle());
		assertThrows(NotFoundException.class, () -> objectiveService.portalListByAgent(999999999L));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_10 门户 /objectives/{oid} → 详情 md + 子项
	// ------------------------------------------------------------
	@Test
	void testPortalDetail() {
		log.info("========== 开始测试 T_obj_10 门户详情 ==========");
		Agent agent = newAgent("o10");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("详情目标"));
		ObjectiveDetailVO vo = objectiveService.portalDetail(obj.getOid());
		assertNotNull(vo.getContentMd(), "详情应含 md 全文");
		assertTrue(vo.getContentMd().contains("达成某某"), "md 应含描述内容");
		assertNotNull(vo.getSubItems(), "应解析子项列表");
		assertEquals(0, vo.getSubItems().size(), "空模板子项应为 0");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_11 软删 → is_deleted=1，门户不再展示，md 保留
	// ------------------------------------------------------------
	@Test
	void testSoftDelete() {
		log.info("========== 开始测试 T_obj_11 软删 ==========");
		Agent agent = newAgent("o11");
		Objective obj = objectiveService.create(agent.getId(), agent.getName(), req("将被软删"));
		objectiveService.delete(agent.getId(), obj.getOid());
		// 索引行不可见（@TableLogic 过滤 is_deleted=1）
		assertNull(objectiveService.findByOid(obj.getOid()), "软删后 findByOid 应为 null");
		// 门户不再展示
		List<ObjectiveVO> list = objectiveService.portalListByAgent(agent.getId());
		assertEquals(0, list.size(), "门户列表不应含已软删目标");
		// 管理端分页不返回
		assertEquals(0, objectiveService.pageSearch(1, 10, agent.getId(), null).getTotal());
		// md 文件保留
		assertTrue(objectiveStorageManager.readRaw(obj.getBodyPath()).contains("## 子项"),
				"软删后 md 文件应保留");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_14 非规范 oid → 404（防注入）
	// ------------------------------------------------------------
	@Test
	void testInvalidOidNotFound() {
		log.info("========== 开始测试 T_obj_14 非规范 oid 404 ==========");
		assertNull(objectiveService.findByOid("o_bad!!id"), "非法 oid 应返回 null");
		assertNull(objectiveService.findByOid("../etc/passwd"), "路径穿越 oid 应返回 null");
		assertNull(objectiveService.findByOid("o_"), "前缀 o_ 无随机段应返回 null");
		assertThrows(NotFoundException.class, () -> objectiveService.portalDetail("o_bad!!id"));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_obj_15 门户分页 status 筛选 → 仅返回对应状态（缺省全部）
	// ------------------------------------------------------------
	@Test
	void testPortalPageStatusFilter() {
		log.info("========== 开始测试 T_obj_15 门户分页 status 筛选 ==========");
		Agent agent = newAgent("o15");
		Objective doing = objectiveService.create(agent.getId(), agent.getName(), req("进行中目标"));
		Objective done = objectiveService.create(agent.getId(), agent.getName(), req("已完成目标"));
		ObjectivePatchReq toDoing = new ObjectivePatchReq();
		toDoing.setStatus(1);
		objectiveService.patchMeta(agent.getId(), doing.getOid(), toDoing);
		ObjectivePatchReq toDone = new ObjectivePatchReq();
		toDone.setStatus(2);
		objectiveService.patchMeta(agent.getId(), done.getOid(), toDone);

		// 缺省 status → 全部
		assertEquals(2, objectiveService.portalPageByAgent(agent.getId(), null, 1, 20).getTotal(),
				"缺省 status 应返回全部");
		// status=1 → 仅进行中
		IPage<ObjectiveVO> doingPage = objectiveService.portalPageByAgent(agent.getId(), 1, 1, 20);
		assertEquals(1, doingPage.getTotal(), "status=1 应仅进行中");
		assertEquals("进行中目标", doingPage.getRecords().get(0).getTitle());
		// status=2 → 仅已完成
		assertEquals(1, objectiveService.portalPageByAgent(agent.getId(), 2, 1, 20).getTotal(),
				"status=2 应仅已完成");
		// status=0 → 空（两条均已改状态）
		assertEquals(0, objectiveService.portalPageByAgent(agent.getId(), 0, 1, 20).getTotal(),
				"status=0 应为空");
		// agent 不存在 → 404
		assertThrows(NotFoundException.class,
				() -> objectiveService.portalPageByAgent(999999999L, null, 1, 20));
		log.info("========== 测试执行完成 ==========");
	}
}
