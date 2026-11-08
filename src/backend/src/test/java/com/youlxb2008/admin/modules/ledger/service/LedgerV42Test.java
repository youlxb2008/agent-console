package com.youlxb2008.admin.modules.ledger.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.ledger.dto.LedgerCreateReq;
import com.youlxb2008.admin.modules.ledger.dto.LedgerPatchReq;
import com.youlxb2008.admin.modules.ledger.entity.Ledger;
import com.youlxb2008.admin.modules.ledger.manager.LedgerStorageManager;
import com.youlxb2008.admin.modules.ledger.vo.LedgerDetailVO;
import com.youlxb2008.admin.modules.ledger.vo.LedgerVO;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M4.2 台账模块集成测试（service 层，映射任务书验收口径）
 * <p>
 * 覆盖：body 双轨切分（短 inline / 长 md 落盘）/ 类型与标题校验 / 引用列 /
 * 列表流筛选 / 非本人 404（防枚举）/ 空 PATCH 400 / PATCH 双轨互转（长→短 / 短→长）/
 * 管理端分页 / 按 agent 查 / 门户只读（单 agent + 聚合）/ 软删（md 保留）/ 非规范 lid。
 * <p>
 * scope 403 属 Controller/ScopeAspect 层，由 e2e（research/m42_ledger_e2e.py）覆盖，
 * 本测试为 service 层不涉及。
 * <p>
 * 使用独立内存 H2（SqlInitializer 执行 agent-domain.sql 自动建 t_ledger）与
 * 临时 LEDGER_ROOT 目录，不触碰开发库/真实数据；全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcledger;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录与台账文件目录全部落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-ledger",
		"ledger.root=./target/test-ledgers" })
@Transactional
@Rollback(true)
class LedgerV42Test {

	@Resource
	private AgentService agentService;

	@Resource
	private LedgerService ledgerService;

	@Resource
	private LedgerStorageManager ledgerStorageManager;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private LedgerCreateReq shortReq(String title) {
		LedgerCreateReq r = new LedgerCreateReq();
		r.setType("progress");
		r.setTitle(title);
		r.setBody("完成巡检并输出报告"); // ≤500 字 → inline
		return r;
	}

	/** 生成 >500 字的长正文（中文），验证 md 落盘 */
	private String longBody() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			sb.append("今日巡检与运维观察记录第").append(i).append("段：节点状态正常，无告警，持续监控中。");
		}
		return sb.toString();
	}

	// ------------------------------------------------------------
	// T_led_01 短 body（≤500 字）→ body_inline 存库，body_path 为 null
	// ------------------------------------------------------------
	@Test
	void testShortBodyInline() {
		log.info("========== 开始测试 T_led_01 短 body 双轨 inline ==========");
		Agent agent = newAgent("led01");
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), shortReq("短正文"));
		assertNotNull(row.getLid(), "应生成 lid");
		assertTrue(row.getLid().matches("^l_[0-9A-Za-z]{10}$"), "lid 应为 l_+10位base62");
		assertEquals("progress", row.getType());
		assertEquals("完成巡检并输出报告", row.getBodyInline(), "短 body 应存 inline");
		assertNull(row.getBodyPath(), "短 body 不应写 md");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_02 长 body（>500 字）→ 写 LEDGER_ROOT/agent-{id}/ledger-{lid}.md + body_path
	// ------------------------------------------------------------
	@Test
	void testLongBodyWritesMd() {
		log.info("========== 开始测试 T_led_02 长 body md 落盘 ==========");
		Agent agent = newAgent("led02");
		LedgerCreateReq req = new LedgerCreateReq();
		req.setType("observation");
		req.setTitle("巡检观察");
		req.setBody(longBody());
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), req);
		assertNull(row.getBodyInline(), "长 body 不应存 inline");
		assertEquals("agent-" + agent.getId() + "/ledger-" + row.getLid() + ".md", row.getBodyPath());
		// md 已落盘且内容一致（truth source 可读）
		String raw = ledgerStorageManager.readRaw(row.getBodyPath());
		assertEquals(longBody(), raw, "md 文件内容应与提交一致");
		// 详情读正文（长 body 走 md）
		LedgerDetailVO vo = ledgerService.getDetail(row.getLid());
		assertEquals(longBody(), vo.getBody(), "详情应返回 md 全文");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_03 类型/标题校验：非法 type 400、空白 title 400
	// ------------------------------------------------------------
	@Test
	void testTypeAndTitleValidation() {
		log.info("========== 开始测试 T_led_03 类型/标题校验 ==========");
		Agent agent = newAgent("led03");
		LedgerCreateReq badType = new LedgerCreateReq();
		badType.setType("unknown");
		badType.setTitle("坏类型");
		assertThrows(BadRequestException.class,
				() -> ledgerService.create(agent.getId(), agent.getName(), badType));

		LedgerCreateReq blankTitle = new LedgerCreateReq();
		blankTitle.setType("memo");
		blankTitle.setTitle("   ");
		assertThrows(BadRequestException.class,
				() -> ledgerService.create(agent.getId(), agent.getName(), blankTitle));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_04 引用列（ref 四列可选并存）保存与回显
	// ------------------------------------------------------------
	@Test
	void testRefColumns() {
		log.info("========== 开始测试 T_led_04 引用列 ==========");
		Agent agent = newAgent("led04");
		LedgerCreateReq req = new LedgerCreateReq();
		req.setType("decision");
		req.setTitle("方向调整");
		req.setBody("决定升级网关版本");
		req.setRefTaskId("t_Abc1234567");
		req.setRefRequirementId("r_Xyz9876543");
		req.setRefObjectiveId("o_Qwe4567890");
		req.setRefNoteNid("n_Zxc0987654");
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), req);
		LedgerVO vo = ledgerService.listOwn(agent.getId(), null, null, null).get(0);
		assertEquals("t_Abc1234567", vo.getRefTaskId(), "ref_task_id 应回显");
		assertEquals("r_Xyz9876543", vo.getRefRequirementId(), "ref_requirement_id 应回显");
		assertEquals("o_Qwe4567890", vo.getRefObjectiveId(), "ref_objective_id 应回显");
		assertEquals("n_Zxc0987654", vo.getRefNoteNid(), "ref_note_nid 应回显");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_05 列表流筛选（type / refTaskId / refRequirementId）
	// ------------------------------------------------------------
	@Test
	void testListFilters() {
		log.info("========== 开始测试 T_led_05 列表流筛选 ==========");
		Agent agent = newAgent("led05");
		LedgerCreateReq a = new LedgerCreateReq();
		a.setType("progress");
		a.setTitle("任务推进 A");
		a.setRefTaskId("t_AAA0000001");
		ledgerService.create(agent.getId(), agent.getName(), a);
		LedgerCreateReq b = new LedgerCreateReq();
		b.setType("decision");
		b.setTitle("拍板 B");
		b.setRefRequirementId("r_BBB0000002");
		ledgerService.create(agent.getId(), agent.getName(), b);

		assertEquals(1, ledgerService.listOwn(agent.getId(), "progress", null, null).size(), "type 筛选");
		assertEquals(1, ledgerService.listOwn(agent.getId(), null, "t_AAA0000001", null).size(), "refTask 筛选");
		assertEquals(1, ledgerService.listOwn(agent.getId(), null, null, "r_BBB0000002").size(), "refReq 筛选");
		assertEquals(2, ledgerService.listOwn(agent.getId(), null, null, null).size(), "无筛选全量");
		// 倒序：后记的 B 排前面（create_time 倒序）
		assertEquals("拍板 B", ledgerService.listOwn(agent.getId(), null, null, null).get(0).getTitle());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_06 非本人 PATCH/DELETE → 404（防枚举）
	// ------------------------------------------------------------
	@Test
	void testOthersPatchDeleteNotFound() {
		log.info("========== 开始测试 T_led_06 非本人 404 ==========");
		Agent owner = newAgent("led06a");
		Agent other = newAgent("led06b");
		Ledger row = ledgerService.create(owner.getId(), owner.getName(), shortReq("私域流水"));
		LedgerPatchReq patch = new LedgerPatchReq();
		patch.setTitle("想篡改");
		assertThrows(NotFoundException.class,
				() -> ledgerService.patchMeta(other.getId(), row.getLid(), patch));
		assertThrows(NotFoundException.class,
				() -> ledgerService.delete(other.getId(), row.getLid()));
		// 流水仍在（未被篡改/删除）
		assertNotNull(ledgerService.findByLid(row.getLid()));
		// 不存在的 lid 同样 404（防枚举）
		assertThrows(NotFoundException.class,
				() -> ledgerService.patchMeta(owner.getId(), "l_ZZZZZZZZZZ", patch));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_07 空 PATCH body → 400
	// ------------------------------------------------------------
	@Test
	void testPatchAllEmptyBadRequest() {
		log.info("========== 开始测试 T_led_07 空 PATCH 400 ==========");
		Agent agent = newAgent("led07");
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), shortReq("空 body"));
		assertThrows(BadRequestException.class,
				() -> ledgerService.patchMeta(agent.getId(), row.getLid(), new LedgerPatchReq()));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_08 PATCH body 双轨互转：短→长（新写 md）/ 长→短（回退 inline，原 md 保留）
	// ------------------------------------------------------------
	@Test
	void testPatchBodyDualTrackSwitch() {
		log.info("========== 开始测试 T_led_08 PATCH 双轨互转 ==========");
		Agent agent = newAgent("led08");
		// 短→长
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), shortReq("先短后长"));
		LedgerPatchReq toLong = new LedgerPatchReq();
		toLong.setBody(longBody());
		Ledger updated = ledgerService.patchMeta(agent.getId(), row.getLid(), toLong);
		assertNull(updated.getBodyInline(), "长 body 后 inline 应为 null");
		assertEquals("agent-" + agent.getId() + "/ledger-" + row.getLid() + ".md", updated.getBodyPath());
		// 长→短
		LedgerPatchReq toShort = new LedgerPatchReq();
		toShort.setBody("缩成一句话");
		Ledger updated2 = ledgerService.patchMeta(agent.getId(), row.getLid(), toShort);
		assertEquals("缩成一句话", updated2.getBodyInline(), "短 body 应回退 inline");
		assertNull(updated2.getBodyPath(), "短 body 应清空 body_path");
		// 原 md 文件保留不删（幂等安全）
		assertTrue(ledgerStorageManager.readRaw("agent-" + agent.getId() + "/ledger-" + row.getLid() + ".md")
				.equals(longBody()), "长正文 md 文件应保留");
		// 详情读正文 = 最新 inline
		LedgerDetailVO vo = ledgerService.getDetail(row.getLid());
		assertEquals("缩成一句话", vo.getBody());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_09 管理端分页（agentId / type 筛选，create_time 倒序）
	// ------------------------------------------------------------
	@Test
	void testAdminPageSearch() {
		log.info("========== 开始测试 T_led_09 管理端分页 ==========");
		Agent agent = newAgent("led09");
		ledgerService.create(agent.getId(), agent.getName(), shortReq("流水一"));
		LedgerCreateReq decision = new LedgerCreateReq();
		decision.setType("decision");
		decision.setTitle("流水二决策");
		decision.setBody("拍板");
		ledgerService.create(agent.getId(), agent.getName(), decision);
		// type 筛选
		var page = ledgerService.pageSearch(1, 10, null, "decision");
		assertEquals(1, page.getTotal(), "type 筛选应命中 1 条");
		// agentId 筛选
		var page2 = ledgerService.pageSearch(1, 10, agent.getId(), null);
		assertEquals(2, page2.getTotal(), "该 agent 应有 2 条");
		// 组合
		var page3 = ledgerService.pageSearch(1, 10, agent.getId(), "progress");
		assertEquals(1, page3.getTotal(), "组合筛选应命中 1 条");
		// 全量 ≥2
		var page4 = ledgerService.pageSearch(1, 10, null, null);
		assertTrue(page4.getTotal() >= 2, "全量应 ≥2 条");
		// agentName 已回填
		assertNotNull(page2.getRecords().get(0).getAgentName(), "应回填 agentName");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_10 管理端按 agent 查（agent 不存在 → 404）
	// ------------------------------------------------------------
	@Test
	void testAdminListByAgent() {
		log.info("========== 开始测试 T_led_10 按 agent 查 ==========");
		Agent agent = newAgent("led10");
		ledgerService.create(agent.getId(), agent.getName(), shortReq("流水甲"));
		ledgerService.create(agent.getId(), agent.getName(), shortReq("流水乙"));
		List<LedgerVO> list = ledgerService.listByAgent(agent.getId());
		assertEquals(2, list.size(), "该 agent 应有 2 条流水");
		assertEquals(agent.getId(), list.get(0).getAgentId(), "应回填 agentId");
		assertNotNull(list.get(0).getAgentName(), "应回填 agentName");
		assertThrows(NotFoundException.class, () -> ledgerService.listByAgent(999999999L));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_11 门户只读：单 agent 流 + 全部 agent 聚合流（含 agentName）
	// ------------------------------------------------------------
	@Test
	void testPortalListAndAggregate() {
		log.info("========== 开始测试 T_led_11 门户只读 ==========");
		Agent agent = newAgent("led11");
		ledgerService.create(agent.getId(), agent.getName(), shortReq("门户流水"));
		// 单 agent 流
		List<LedgerVO> byAgent = ledgerService.portalListByAgent(agent.getId());
		assertEquals(1, byAgent.size());
		assertNotNull(byAgent.get(0).getAgentName(), "单 agent 流应回填 agentName");
		assertThrows(NotFoundException.class, () -> ledgerService.portalListByAgent(999999999L));
		// 聚合流
		List<LedgerVO> agg = ledgerService.portalAggregate();
		assertTrue(agg.size() >= 1, "聚合流应 ≥1 条");
		assertNotNull(agg.get(0).getAgentName(), "聚合流应回填 agentName");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_12 软删 → is_deleted=1，列表/详情/门户不再展示，md 文件保留
	// ------------------------------------------------------------
	@Test
	void testSoftDelete() {
		log.info("========== 开始测试 T_led_12 软删 ==========");
		Agent agent = newAgent("led12");
		LedgerCreateReq req = new LedgerCreateReq();
		req.setType("session");
		req.setTitle("长会话小结");
		req.setBody(longBody());
		Ledger row = ledgerService.create(agent.getId(), agent.getName(), req);
		ledgerService.delete(agent.getId(), row.getLid());
		// 索引行不可见（@TableLogic 过滤）
		assertNull(ledgerService.findByLid(row.getLid()), "软删后 findByLid 应为 null");
		// 详情 → 404
		assertThrows(NotFoundException.class, () -> ledgerService.getDetail(row.getLid()));
		// 门户不再展示
		assertEquals(0, ledgerService.portalListByAgent(agent.getId()).size());
		// 管理端分页不返回
		assertEquals(0, ledgerService.pageSearch(1, 10, agent.getId(), null).getTotal());
		// md 文件保留
		assertTrue(ledgerStorageManager.readRaw(row.getBodyPath()).equals(longBody()),
				"软删后 md 文件应保留");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_led_13 非规范 lid → 404（防注入）
	// ------------------------------------------------------------
	@Test
	void testInvalidLidNotFound() {
		log.info("========== 开始测试 T_led_13 非规范 lid 404 ==========");
		assertNull(ledgerService.findByLid("l_bad!!id"), "非法 lid 应返回 null");
		assertNull(ledgerService.findByLid("../etc/passwd"), "路径穿越 lid 应返回 null");
		assertNull(ledgerService.findByLid("l_"), "前缀 l_ 无随机段应返回 null");
		assertThrows(NotFoundException.class, () -> ledgerService.getDetail("l_bad!!id"));
		assertThrows(NotFoundException.class,
				() -> ledgerService.getDetail("agent-1/ledger-l_Abc1234567.md"), "带路径的 lid 应 404");
		log.info("========== 测试执行完成 ==========");
	}
}
