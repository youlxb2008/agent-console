package com.youlxb2008.admin.modules.todo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.todo.dto.TodoCreateReq;
import com.youlxb2008.admin.modules.todo.dto.TodoPatchReq;
import com.youlxb2008.admin.modules.todo.entity.Todo;
import com.youlxb2008.admin.modules.todo.vo.TodoVO;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M5.3 待办截止日期（due_date）集成测试（service 层，映射任务书验收口径）
 * <p>
 * 覆盖：创建带 dueDate（VO 回显 + entity 落库）/ PATCH 修改 dueDate（null=不修改）/
 * 排序断言（有截止日期在前按日期升序、同日期按 priority DESC；无日期在后按 priority DESC, id ASC）/
 * 门户排序同 agent 域。
 * <p>
 * 环境同 TodoV52Test：独立内存 H2（SqlInitializer 执行 agent-domain.sql 自动建表含
 * due_date 列与 idx_t_todo_due_date），全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agctodo53;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		"web.data-root=./target/test-data-todo53" })
@Transactional
@Rollback(true)
class TodoV53Test {

	@Resource
	private AgentService agentService;

	@Resource
	private TodoService todoService;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private TodoCreateReq req(String title, Integer priority) {
		TodoCreateReq r = new TodoCreateReq();
		r.setTitle(title);
		r.setPriority(priority);
		return r;
	}

	/** yyyy-MM-dd 字符串 → Date（系统默认时区当天 00:00） */
	private Date date(String ymd) {
		try {
			return new SimpleDateFormat("yyyy-MM-dd").parse(ymd);
		} catch (ParseException e) {
			throw new IllegalStateException("日期字面量非法: " + ymd, e);
		}
	}

	/** Date → yyyy-MM-dd（忽略时间分量，规避 H2 DATE 回读的时分秒差异） */
	private String ymd(Date d) {
		return d == null ? null : new SimpleDateFormat("yyyy-MM-dd").format(d);
	}

	// ------------------------------------------------------------
	// T_todo53_01 创建带 dueDate：VO 回显 yyyy-MM-dd + entity 落库
	// ------------------------------------------------------------
	@Test
	void testCreateWithDueDate() {
		log.info("========== 开始测试 T_todo53_01 创建带截止日期 ==========");
		Agent agent = newAgent("todo53a");
		TodoCreateReq r = req("带截止日期待办", 2);
		r.setDueDate(date("2026-09-15"));
		TodoVO vo = todoService.create(agent.getId(), r);
		assertNotNull(vo.getDueDate(), "dueDate 应回显");
		assertEquals("2026-09-15", ymd(vo.getDueDate()), "dueDate 应为 yyyy-MM-dd 语义");

		// entity 回读：真相源即索引行
		Todo row = todoService.findByTodoId(vo.getTodoId());
		assertNotNull(row);
		assertNotNull(row.getDueDate(), "due_date 应落库");
		assertEquals("2026-09-15", ymd(row.getDueDate()));

		// 不传 dueDate → 保持 null（可空语义）
		TodoVO noDue = todoService.create(agent.getId(), req("无截止日期", 3));
		assertNull(noDue.getDueDate(), "不传 dueDate 应为 null");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo53_02 PATCH：dueDate null=不修改；传值=修改；与 title 混合子集
	// ------------------------------------------------------------
	@Test
	void testPatchDueDate() {
		log.info("========== 开始测试 T_todo53_02 PATCH 截止日期 ==========");
		Agent agent = newAgent("todo53b");
		TodoCreateReq r = req("PATCH 前待办", 3);
		r.setDueDate(date("2026-09-20"));
		TodoVO vo = todoService.create(agent.getId(), r);

		// 只改 title（dueDate 不传 = 不修改）
		TodoPatchReq patchTitle = new TodoPatchReq();
		patchTitle.setTitle("PATCH 后待办");
		TodoVO afterTitle = todoService.patchMeta(agent.getId(), vo.getTodoId(), patchTitle);
		assertEquals("PATCH 后待办", afterTitle.getTitle());
		assertEquals("2026-09-20", ymd(afterTitle.getDueDate()), "dueDate null 应不修改");

		// 改 dueDate
		TodoPatchReq patchDue = new TodoPatchReq();
		patchDue.setDueDate(date("2026-09-25"));
		TodoVO afterDue = todoService.patchMeta(agent.getId(), vo.getTodoId(), patchDue);
		assertEquals("2026-09-25", ymd(afterDue.getDueDate()), "dueDate 应更新");
		assertEquals("PATCH 后待办", afterDue.getTitle(), "未传字段不受影响");

		// 只传 dueDate 也算「至少一项」（全空 400 的反面）
		TodoPatchReq onlyDue = new TodoPatchReq();
		onlyDue.setDueDate(date("2026-09-28"));
		TodoVO afterOnly = todoService.patchMeta(agent.getId(), vo.getTodoId(), onlyDue);
		assertEquals("2026-09-28", ymd(afterOnly.getDueDate()));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo53_03 排序：(due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC
	// 有日期在前按日期升序（同日期 priority DESC）；无日期在后按 priority DESC
	// ------------------------------------------------------------
	@Test
	void testSortWithDueDate() {
		log.info("========== 开始测试 T_todo53_03 截止日期排序 ==========");
		Agent agent = newAgent("todo53c");
		// 故意打乱创建顺序，避免 id 顺序与断言巧合
		todoService.create(agent.getId(), withDue(req("无日期高优", 5), null));            // N1
		todoService.create(agent.getId(), withDue(req("晚日期", 5), "2026-09-15"));        // E3
		todoService.create(agent.getId(), withDue(req("早日期低优", 1), "2026-09-12"));    // E1
		todoService.create(agent.getId(), withDue(req("无日期低优", 1), null));            // N2
		todoService.create(agent.getId(), withDue(req("早日期中优", 3), "2026-09-12"));    // E2

		IPage<TodoVO> page = todoService.pageOwn(agent.getId(), null, null, 1, 20);
		List<TodoVO> list = page.getRecords();
		assertEquals(5, page.getTotal(), "应 5 条");
		assertEquals("早日期中优", list.get(0).getTitle(), "同日期 09-12 按 priority DESC：p3 在前");
		assertEquals("早日期低优", list.get(1).getTitle(), "同日期 09-12 p1 在后");
		assertEquals("晚日期", list.get(2).getTitle(), "有日期按 dueDate ASC：09-15 次之");
		assertEquals("无日期高优", list.get(3).getTitle(), "无日期按 priority DESC：p5 在前");
		assertEquals("无日期低优", list.get(4).getTitle(), "无日期 p1 最后");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo53_04 门户排序同 agent 域：有日期在前按升序，无日期按 priority
	// ------------------------------------------------------------
	@Test
	void testPortalSortWithDueDate() {
		log.info("========== 开始测试 T_todo53_04 门户截止日期排序 ==========");
		Agent agent = newAgent("todo53d");
		todoService.create(agent.getId(), withDue(req("门户无日期", 5), null));
		todoService.create(agent.getId(), withDue(req("门户有日期", 1), "2026-09-12"));

		IPage<TodoVO> page = todoService.portalPageByAgent(agent.getId(), null, 1, 20);
		List<TodoVO> list = page.getRecords();
		assertEquals(2, page.getTotal(), "门户应 2 条");
		assertEquals("门户有日期", list.get(0).getTitle(), "有截止日期排最前");
		assertEquals("门户无日期", list.get(1).getTitle());
		log.info("========== 测试执行完成 ==========");
	}

	/** 辅助：设置 dueDate（null 表示不设） */
	private TodoCreateReq withDue(TodoCreateReq r, String ymdStr) {
		if (ymdStr != null) {
			r.setDueDate(date(ymdStr));
		}
		return r;
	}
}
