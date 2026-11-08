package com.youlxb2008.admin.modules.todo.service;

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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.WebApplication;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.todo.dto.TodoCreateReq;
import com.youlxb2008.admin.modules.todo.dto.TodoPatchReq;
import com.youlxb2008.admin.modules.todo.entity.Todo;
import com.youlxb2008.admin.modules.todo.vo.TodoVO;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * M5.2 待办模块集成测试（service 层，映射任务书验收口径）
 * <p>
 * 覆盖：CRUD 全链路 / 越权 403 / 404（不存在、已软删、非法 todoId）/
 * 分页与 status/priority 筛选 / 排序（priority DESC, id ASC）/ 软删后列表与详情不可见 /
 * tags 逗号字符串规范化去重 / 标题与优先级校验 / 空 PATCH 400 / 门户匿名只读分页。
 * <p>
 * scope 403 属 Controller/ScopeAspect 层，由独立 e2e 脚本覆盖，
 * 本测试为 service 层不涉及。
 * <p>
 * 使用独立内存 H2（SqlInitializer 执行 agent-domain.sql 自动建 t_todo）与
 * 临时 web.data-root，不触碰开发库/真实数据；全部 @Transactional 回滚。
 */
@Slf4j
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agctodo;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录落到 maven target 下，隔离真实数据
		"web.data-root=./target/test-data-todo" })
@Transactional
@Rollback(true)
class TodoV52Test {

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

	private TodoCreateReq req(String title, Integer priority, String tags) {
		TodoCreateReq r = new TodoCreateReq();
		r.setTitle(title);
		r.setContent("来源：调研池迁入");
		r.setPriority(priority);
		r.setTags(tags);
		return r;
	}

	// ------------------------------------------------------------
	// T_todo_01 新建：todoId 格式 / 默认值 / tags 规范化 / 字段回显
	// ------------------------------------------------------------
	@Test
	void testCreate() {
		log.info("========== 开始测试 T_todo_01 新建待办 ==========");
		Agent agent = newAgent("todo01");
		TodoVO vo = todoService.create(agent.getId(), req("完成调研池迁移", 1, " 调研 ,调研,池子 "));
		assertNotNull(vo.getTodoId(), "应生成 todoId");
		assertTrue(vo.getTodoId().matches("^d_[0-9A-Za-z]{10}$"), "todoId 应为 d_+10位base62");
		assertEquals("完成调研池迁移", vo.getTitle());
		assertEquals(Integer.valueOf(1), vo.getPriority(), "priority 应回显");
		assertEquals("调研,池子", vo.getTags(), "tags 应去空格、去重、重 join");
		assertEquals(Integer.valueOf(0), vo.getStatus(), "status 默认 0");
		assertNotNull(vo.getCreateTime());
		assertNotNull(vo.getUpdateTime());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_02 默认值：priority 缺省 3 / status 缺省 0 / tags 缺省 null
	// ------------------------------------------------------------
	@Test
	void testCreateDefaults() {
		log.info("========== 开始测试 T_todo_02 默认值 ==========");
		Agent agent = newAgent("todo02");
		TodoCreateReq r = new TodoCreateReq();
		r.setTitle("默认值待办");
		TodoVO vo = todoService.create(agent.getId(), r);
		assertEquals(Integer.valueOf(3), vo.getPriority(), "priority 默认 3");
		assertEquals(Integer.valueOf(0), vo.getStatus(), "status 默认 0");
		assertNull(vo.getTags(), "tags 缺省为 null");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_03 字段校验：空/空白 title 400、超长 title 400、priority/status 越界 400
	// ------------------------------------------------------------
	@Test
	void testValidation() {
		log.info("========== 开始测试 T_todo_03 字段校验 ==========");
		Agent agent = newAgent("todo03");
		TodoCreateReq blankTitle = new TodoCreateReq();
		blankTitle.setTitle("   ");
		assertThrows(BadRequestException.class,
				() -> todoService.create(agent.getId(), blankTitle));

		TodoCreateReq longTitle = new TodoCreateReq();
		longTitle.setTitle("标".repeat(201));
		assertThrows(BadRequestException.class,
				() -> todoService.create(agent.getId(), longTitle));

		TodoCreateReq badPriority = req("坏优先级", 0, null);
		assertThrows(BadRequestException.class,
				() -> todoService.create(agent.getId(), badPriority));

		TodoCreateReq badStatus = req("坏状态", 3, null);
		badStatus.setStatus(9);
		assertThrows(BadRequestException.class,
				() -> todoService.create(agent.getId(), badStatus));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_04 排序：priority DESC, id ASC（调研池消费顺序）
	// ------------------------------------------------------------
	@Test
	void testSortOrder() {
		log.info("========== 开始测试 T_todo_04 排序 ==========");
		Agent agent = newAgent("todo04");
		todoService.create(agent.getId(), req("低优先级", 1, null));
		todoService.create(agent.getId(), req("高优先级", 5, null));
		todoService.create(agent.getId(), req("中优先级", 3, null));
		// 同 priority（5）再插一条验证 id ASC
		todoService.create(agent.getId(), req("高优先级后到", 5, null));

		IPage<TodoVO> page = todoService.pageOwn(agent.getId(), null, null, 1, 20);
		List<TodoVO> list = page.getRecords();
		assertEquals(4, page.getTotal(), "应 4 条");
		assertEquals("高优先级", list.get(0).getTitle(), "priority 5 排最前");
		assertEquals("高优先级后到", list.get(1).getTitle(), "同 priority 5 按 id ASC（后建排后）");
		assertEquals("中优先级", list.get(2).getTitle());
		assertEquals("低优先级", list.get(3).getTitle(), "priority 1 排最后");
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_05 分页 + status/priority 筛选
	// ------------------------------------------------------------
	@Test
	void testPageAndFilter() {
		log.info("========== 开始测试 T_todo_05 分页与筛选 ==========");
		Agent agent = newAgent("todo05");
		todoService.create(agent.getId(), req("待办A", 2, null));
		TodoCreateReq done = req("已完成B", 4, null);
		done.setStatus(2);
		todoService.create(agent.getId(), done);
		todoService.create(agent.getId(), req("待办C", 3, null));

		// status 筛选
		IPage<TodoVO> doing = todoService.pageOwn(agent.getId(), 0, null, 1, 20);
		assertEquals(2, doing.getTotal(), "status=0 应 2 条");
		IPage<TodoVO> donePage = todoService.pageOwn(agent.getId(), 2, null, 1, 20);
		assertEquals(1, donePage.getTotal(), "status=2 应 1 条");

		// priority 筛选
		IPage<TodoVO> p4 = todoService.pageOwn(agent.getId(), null, 4, 1, 20);
		assertEquals(1, p4.getTotal(), "priority=4 应 1 条");

		// 分页 size=2：首页 2 条，第二页 1 条
		IPage<TodoVO> p1 = todoService.pageOwn(agent.getId(), null, null, 1, 2);
		assertEquals(2, p1.getRecords().size());
		IPage<TodoVO> p2 = todoService.pageOwn(agent.getId(), null, null, 2, 2);
		assertEquals(1, p2.getRecords().size());
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_06 详情（含 content）+ 越权 403 + 不存在/非法 todoId 404
	// ------------------------------------------------------------
	@Test
	void testDetailAndOwnership() {
		log.info("========== 开始测试 T_todo_06 详情与归属 ==========");
		Agent owner = newAgent("todo06a");
		Agent other = newAgent("todo06b");
		TodoVO vo = todoService.create(owner.getId(), req("私域待办", 2, "私密"));

		// 本人详情
		TodoVO detail = todoService.getDetailOwned(owner.getId(), vo.getTodoId());
		assertEquals("来源：调研池迁入", detail.getContent(), "详情应含 content");
		assertEquals("私密", detail.getTags());

		// 越权：他人 → 403（契约明确区分 403/404，与 objective 统一 404 不同）
		assertThrows(ForbiddenException.class,
				() -> todoService.getDetailOwned(other.getId(), vo.getTodoId()));

		// 不存在 / 非法 todoId → 404
		assertThrows(NotFoundException.class,
				() -> todoService.getDetailOwned(owner.getId(), "d_ZZZZZZZZZZ"));
		assertThrows(NotFoundException.class,
				() -> todoService.getDetailOwned(owner.getId(), "../etc/passwd"));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_07 PATCH：任意子集 / 越权 403 / 全空 400 / 状态任意流转
	// ------------------------------------------------------------
	@Test
	void testPatch() {
		log.info("========== 开始测试 T_todo_07 PATCH ==========");
		Agent owner = newAgent("todo07a");
		Agent other = newAgent("todo07b");
		TodoVO vo = todoService.create(owner.getId(), req("改前标题", 3, "a"));

		// 子集改：title + status + priority + tags
		TodoPatchReq patch = new TodoPatchReq();
		patch.setTitle("改后标题");
		patch.setStatus(2);
		patch.setPriority(1);
		patch.setTags("b, c");
		TodoVO updated = todoService.patchMeta(owner.getId(), vo.getTodoId(), patch);
		assertEquals("改后标题", updated.getTitle());
		assertEquals(Integer.valueOf(2), updated.getStatus(), "状态任意流转（0→2）");
		assertEquals(Integer.valueOf(1), updated.getPriority());
		assertEquals("b,c", updated.getTags(), "tags 规范化");

		// 全空 → 400
		assertThrows(BadRequestException.class,
				() -> todoService.patchMeta(owner.getId(), vo.getTodoId(), new TodoPatchReq()));

		// 越权 → 403
		TodoPatchReq otherPatch = new TodoPatchReq();
		otherPatch.setTitle("篡改");
		assertThrows(ForbiddenException.class,
				() -> todoService.patchMeta(other.getId(), vo.getTodoId(), otherPatch));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_08 软删：is_deleted=1 → 列表/详情/门户不可见，再次 DELETE 404，越权 403
	// ------------------------------------------------------------
	@Test
	void testSoftDelete() {
		log.info("========== 开始测试 T_todo_08 软删 ==========");
		Agent owner = newAgent("todo08a");
		Agent other = newAgent("todo08b");
		TodoVO vo = todoService.create(owner.getId(), req("将被删除", 3, "x"));
		todoService.delete(owner.getId(), vo.getTodoId());

		// 索引行不可见（@TableLogic 过滤）
		assertNull(todoService.findByTodoId(vo.getTodoId()), "软删后 findByTodoId 应为 null");
		// 详情 → 404
		assertThrows(NotFoundException.class,
				() -> todoService.getDetailOwned(owner.getId(), vo.getTodoId()));
		// 列表不再展示
		assertEquals(0, todoService.pageOwn(owner.getId(), null, null, 1, 20).getTotal());
		// 门户不再展示
		assertEquals(0, todoService.portalPageByAgent(owner.getId(), null, 1, 20).getTotal());
		// 再次 DELETE → 404（幂等防枚举）
		assertThrows(NotFoundException.class, () -> todoService.delete(owner.getId(), vo.getTodoId()));
		// 越权删除他人 → 403
		TodoVO vo2 = todoService.create(owner.getId(), req("别人的", 3, null));
		assertThrows(ForbiddenException.class, () -> todoService.delete(other.getId(), vo2.getTodoId()));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_09 门户匿名只读分页：status 筛选 / agent 不存在 404
	// ------------------------------------------------------------
	@Test
	void testPortalPage() {
		log.info("========== 开始测试 T_todo_09 门户分页 ==========");
		Agent agent = newAgent("todo09");
		todoService.create(agent.getId(), req("门户待办A", 2, "公开"));
		TodoCreateReq done = req("门户待办B", 4, "公开");
		done.setStatus(1);
		todoService.create(agent.getId(), done);

		IPage<TodoVO> all = todoService.portalPageByAgent(agent.getId(), null, 1, 20);
		assertEquals(2, all.getTotal(), "门户应看到 2 条");
		assertEquals(1, todoService.portalPageByAgent(agent.getId(), 1, 1, 20).getTotal(), "status=1 筛选");
		// 排序同 agent 域：priority 4 在前
		assertEquals("门户待办B", all.getRecords().get(0).getTitle());

		assertThrows(NotFoundException.class,
				() -> todoService.portalPageByAgent(999999999L, null, 1, 20));
		log.info("========== 测试执行完成 ==========");
	}

	// ------------------------------------------------------------
	// T_todo_10 数据落库：entity 回读（真相源即索引行，无文件系统）
	// ------------------------------------------------------------
	@Test
	void testEntityPersisted() {
		log.info("========== 开始测试 T_todo_10 实体落库 ==========");
		Agent agent = newAgent("todo10");
		TodoVO vo = todoService.create(agent.getId(), req("落库校验", 2, "tag1"));
		Todo row = todoService.findByTodoId(vo.getTodoId());
		assertNotNull(row, "应按 todoId 查回实体");
		assertEquals(agent.getId(), row.getAgentId(), "agent_id 归属正确");
		assertEquals("落库校验", row.getTitle());
		assertEquals("tag1", row.getTags());
		assertEquals(Integer.valueOf(2), row.getPriority());
		assertEquals(Integer.valueOf(0), row.getStatus());
		assertEquals(Integer.valueOf(0), row.getIsDeleted(), "未删 is_deleted=0");
		log.info("========== 测试执行完成 ==========");
	}
}
