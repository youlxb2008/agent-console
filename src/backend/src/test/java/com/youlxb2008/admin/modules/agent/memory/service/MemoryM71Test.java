package com.youlxb2008.admin.modules.agent.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
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
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryCreateReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemorySupersedeReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryUpdateReq;
import com.youlxb2008.admin.modules.agent.memory.entity.Memory;
import com.youlxb2008.admin.modules.agent.memory.job.MemoryDecayJob;
import com.youlxb2008.admin.modules.agent.memory.manager.MemoryIndexManager;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryDetailVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemorySearchHitVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryVO;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.agent.service.ApiKeyService;

import jakarta.annotation.Resource;

/**
 * M7.1 记忆模块集成测试（service 层 + MockMvc 路由层，映射任务书 §2.4 验收口径）
 * <p>
 * 覆盖：九端点正负路径（无 key 401 / scope 不足 403 / 跨 agent 404 / 参数校验 400）/
 * supersede 链回溯链尾 / search 只召回不改计数、confirm 才 +1（M7.1-M4 价值闭环）/ restore 仅 archived /
 * Lucene 写入后可检索、删除后不可检索、归档可检索带 status 标记 / 同分 weight 排序 /
 * 分页筛选（status/memType/tag）。
 * <p>
 * 使用独立内存 H2 与临时 data-root（Lucene 索引目录随之隔离），不触碰开发库/真实数据；
 * DB 全部 @Transactional 回滚。Lucene 为文件系统不受事务管理：测试内显式调
 * {@code indexNow}/{@code removeNow} 同步索引（事件路 AFTER_COMMIT 在回滚测试事务中不触发），
 * 并在 AfterEach 清理本测试创建的索引文档。
 */
@SpringBootTest(classes = WebApplication.class, properties = {
		// 独立内存 H2（MODE=MySQL 兼容），避免写开发 H2 文件库
		"spring.datasource.dynamic.datasource.primary.url=jdbc:h2:mem:agcmem;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.dynamic.datasource.primary.driver-class-name=org.h2.Driver",
		// 数据根目录落到 maven target 下，隔离真实数据（Lucene 索引目录随之隔离）
		"web.data-root=./target/test-data-memory" })
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
class MemoryM71Test {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Resource
	private AgentService agentService;

	@Resource
	private ApiKeyService apiKeyService;

	@Resource
	private MemoryService memoryService;

	@Resource
	private MemoryIndexManager memoryIndexManager;

	@Resource
	private MemoryDecayJob memoryDecayJob;

	/** 名称后缀，保证同 JVM 内多次调用不重名 */
	private static final AtomicLong SEQ = new AtomicLong(0);

	/** AfterEach 清理：本测试创建过索引的记忆 id（Lucene 不受事务回滚管） */
	private final List<Long> indexedIds = new java.util.ArrayList<>();

	@AfterEach
	void cleanupIndex() {
		for (Long id : indexedIds) {
			try {
				memoryIndexManager.removeMemory(id);
			} catch (Exception ignored) {
				// 清理失败不影响断言（残留文档会被 DB 批查过滤）
			}
		}
		indexedIds.clear();
	}

	private Agent newAgent(String tag) {
		String name = "ag_" + tag + "_" + SEQ.incrementAndGet();
		return agentService.createAgent(name, "显示" + tag, null, "测试 agent", 1, 1L);
	}

	private MemoryCreateReq req(String memType, String source, String title, String content, String tags) {
		MemoryCreateReq r = new MemoryCreateReq();
		r.setMemType(memType);
		r.setSource(source);
		r.setTitle(title);
		r.setContentMd(content);
		r.setTags(tags);
		return r;
	}

	/** 建一条记忆并同步索引（测试辅助；id 记入 AfterEach 清理清单） */
	private MemoryVO createIndexed(Long agentId, String memType, String source, String title,
			String content, String tags) {
		MemoryVO vo = memoryService.create(agentId, req(memType, source, title, content, tags));
		memoryService.getById(vo.getId()); // 事务内可见
		memoryIndexManager.indexMemory(vo.getId(), agentId, title, vo.getTags(), content, 0);
		indexedIds.add(vo.getId());
		return vo;
	}

	// ------------------------------------------------------------
	// T_mem_01 写入：默认值 / tags 规范化 / 字段回显
	// ------------------------------------------------------------
	@Test
	void testCreate() {
		log("T_mem_01 写入记忆");
		Agent agent = newAgent("mem01");
		MemoryVO vo = memoryService.create(agent.getId(),
				req("fact", "dialogue", "用户偏好简洁回复", "用户明确表示偏好简短直接的回复风格", " 偏好 ,偏好,沟通 "));
		assertNotNull(vo.getId(), "应生成自增 id");
		assertEquals("fact", vo.getMemType());
		assertEquals("dialogue", vo.getSource());
		assertEquals(Integer.valueOf(1), vo.getStatus(), "status 默认 1=active");
		assertEquals(Integer.valueOf(0), vo.getWeight(), "weight 默认 0");
		assertEquals(Integer.valueOf(0), vo.getAccessCount(), "accessCount 默认 0");
		assertEquals("偏好,沟通", vo.getTags(), "tags 应去空格、去重、重 join");
		assertNull(vo.getSupersededBy(), "初始无取代链");
		assertNotNull(vo.getCreateTime());
	}

	// ------------------------------------------------------------
	// T_mem_02 参数校验：memType/source 白名单 400、空 title/contentMd 400
	// ------------------------------------------------------------
	@Test
	void testValidation() {
		log("T_mem_02 参数校验");
		Agent agent = newAgent("mem02");
		// memType 白名单外 → 400
		assertThrows(BadRequestException.class,
				() -> memoryService.create(agent.getId(), req("wrong", "self", "t", "c", null)),
				"memType 白名单外应 400");
		// source 白名单外 → 400
		assertThrows(BadRequestException.class,
				() -> memoryService.create(agent.getId(), req("fact", "other", "t", "c", null)),
				"source 白名单外应 400");
		// 空 title → 400
		assertThrows(BadRequestException.class,
				() -> memoryService.create(agent.getId(), req("fact", "self", " ", "c", null)),
				"空 title 应 400");
		// 空 contentMd → 400
		MemoryCreateReq r = req("fact", "self", "t", " ", null);
		assertThrows(BadRequestException.class, () -> memoryService.create(agent.getId(), r),
				"空 contentMd 应 400");
	}

	// ------------------------------------------------------------
	// T_mem_03 跨 agent 404 fail-closed（任务书硬性：不泄露存在性）
	// ------------------------------------------------------------
	@Test
	void testCrossAgent404() {
		log("T_mem_03 跨 agent 404");
		Agent owner = newAgent("mem03a");
		Agent other = newAgent("mem03b");
		MemoryVO vo = memoryService.create(owner.getId(), req("fact", "self", "私有记忆", "仅 owner 可见", null));

		Long otherId = other.getId();
		Long memId = vo.getId();
		// 跨 agent 读详情 → 404（不是 403）
		assertThrows(NotFoundException.class, () -> memoryService.detail(otherId, memId), "跨 agent 详情应 404");
		assertThrows(NotFoundException.class, () -> memoryService.requireOwned(otherId, memId), "跨 agent 定位应 404");
		// 跨 agent 写操作 → 404
		assertThrows(NotFoundException.class,
				() -> memoryService.update(otherId, memId, new MemoryUpdateReq()), "跨 agent 更新应 404");
		assertThrows(NotFoundException.class, () -> memoryService.confirm(otherId, memId), "跨 agent 确认应 404");
		assertThrows(NotFoundException.class,
				() -> memoryService.restore(otherId, memId), "跨 agent 恢复应 404");
		assertThrows(NotFoundException.class, () -> memoryService.delete(otherId, memId), "跨 agent 删除应 404");
		// 不存在的 id → 404
		assertThrows(NotFoundException.class, () -> memoryService.detail(owner.getId(), 999999L),
				"不存在 id 应 404");
	}

	// ------------------------------------------------------------
	// T_mem_04 更新：任意子集 / 全空 400 / memType 白名单复检
	// ------------------------------------------------------------
	@Test
	void testUpdate() {
		log("T_mem_04 更新记忆");
		Agent agent = newAgent("mem04");
		MemoryVO vo = memoryService.create(agent.getId(), req("fact", "self", "旧标题", "旧正文", "旧标签"));

		MemoryUpdateReq patch = new MemoryUpdateReq();
		patch.setTitle("新标题");
		patch.setTags("新标签,补充");
		MemoryVO updated = memoryService.update(agent.getId(), vo.getId(), patch);
		assertEquals("新标题", updated.getTitle());
		assertEquals("新标签,补充", updated.getTags());
		assertEquals("旧正文", memoryService.getById(vo.getId()).getContentMd(), "未传字段不修改");

		// 全空 → 400
		assertThrows(BadRequestException.class,
				() -> memoryService.update(agent.getId(), vo.getId(), new MemoryUpdateReq()), "全空 PATCH 应 400");
		// memType 白名单复检 → 400
		MemoryUpdateReq badType = new MemoryUpdateReq();
		badType.setMemType("wrong");
		assertThrows(BadRequestException.class,
				() -> memoryService.update(agent.getId(), vo.getId(), badType), "memType 白名单复检应 400");
	}

	// ------------------------------------------------------------
	// T_mem_05 confirm：confirmed_at 刷新
	// ------------------------------------------------------------
	@Test
	void testConfirm() {
		log("T_mem_05 确认记忆");
		Agent agent = newAgent("mem05");
		MemoryVO vo = memoryService.create(agent.getId(), req("lesson", "self", "教训", "踩坑记录", null));
		assertNull(vo.getConfirmedAt(), "初始 confirmed_at 为空");
		memoryService.confirm(agent.getId(), vo.getId());
		assertNotNull(memoryService.getById(vo.getId()).getConfirmedAt(), "confirm 后 confirmed_at 应刷新");
	}

	// ------------------------------------------------------------
	// T_mem_06 supersede 链：创建→取代→详情回溯链尾（任务书 §2.4）
	// ------------------------------------------------------------
	@Test
	void testSupersedeChain() {
		log("T_mem_06 supersede 链回溯");
		Agent agent = newAgent("mem06");
		MemoryVO v1 = memoryService.create(agent.getId(), req("fact", "dialogue", "部署端口 8900", "初版结论：端口 8900", null));
		MemoryVO v2 = memoryService.create(agent.getId(), req("fact", "dialogue", "部署端口 8901", "修订结论：端口改为 8901", null));
		MemoryVO v3 = memoryService.create(agent.getId(), req("fact", "dialogue", "部署端口 8902", "再修订：端口改为 8902", null));

		// v1 被 v2 取代（关联已有）
		MemorySupersedeReq sup1 = new MemorySupersedeReq();
		sup1.setNewMemoryId(v2.getId());
		MemoryVO newV1 = memoryService.supersede(agent.getId(), v1.getId(), sup1);
		assertEquals(v2.getId(), newV1.getId(), "取代应返回新条");
		assertEquals(3, memoryService.getById(v1.getId()).getStatus(), "旧条应置 3=superseded");
		assertEquals(v2.getId(), memoryService.getById(v1.getId()).getSupersededBy(), "旧条应指向新条");

		// v2 被 v3 取代（直接带新记忆字段创建路径）
		MemorySupersedeReq sup2 = new MemorySupersedeReq();
		sup2.setMemType("fact");
		sup2.setSource("dialogue");
		sup2.setTitle("部署端口 8902");
		sup2.setContentMd("再修订：端口改为 8902");
		MemoryVO created = memoryService.supersede(agent.getId(), v2.getId(), sup2);
		assertNotNull(created.getId(), "路径 B 应创建新条");
		assertEquals(3, memoryService.getById(v2.getId()).getStatus(), "v2 应置 3");

		// 详情回溯：v1 → 链尾（v3 或新建条）
		MemoryDetailVO detail = memoryService.detail(agent.getId(), v1.getId());
		assertEquals(3, detail.getMemory().getStatus(), "本条 status=3");
		assertNotNull(detail.getLatest(), "被取代条详情应回溯出链尾");
		assertEquals(1, detail.getLatest().getStatus(), "链尾应为活跃条");
		assertEquals(3, detail.getChain().size(), "链途经节点应含 v1/中间/链尾");
		// 活跃条详情：latest=null
		MemoryDetailVO tailDetail = memoryService.detail(agent.getId(), detail.getLatest().getId());
		assertNull(tailDetail.getLatest(), "链尾详情 latest 应为 null");

		// 取代自己 → 400；取代非活跃条 → 400；空 body → 400
		MemorySupersedeReq self = new MemorySupersedeReq();
		self.setNewMemoryId(v1.getId());
		assertThrows(BadRequestException.class,
				() -> memoryService.supersede(agent.getId(), v1.getId(), self), "不能以自己取代自己");
		MemorySupersedeReq toArchived = new MemorySupersedeReq();
		toArchived.setNewMemoryId(v1.getId());
		assertThrows(BadRequestException.class,
				() -> memoryService.supersede(agent.getId(), v3.getId(), toArchived), "新条非活跃应 400");
		assertThrows(BadRequestException.class,
				() -> memoryService.supersede(agent.getId(), v3.getId(), new MemorySupersedeReq()),
				"空 body 应 400");
	}

	// ------------------------------------------------------------
	// T_mem_07 restore：仅 archived（2→1）可恢复
	// ------------------------------------------------------------
	@Test
	void testRestoreOnlyArchived() {
		log("T_mem_07 恢复仅限归档");
		Agent agent = newAgent("mem07");
		MemoryVO vo = memoryService.create(agent.getId(), req("preference", "self", "偏好", "内容", null));

		// 活跃条直接恢复 → 400
		assertThrows(BadRequestException.class, () -> memoryService.restore(agent.getId(), vo.getId()),
				"活跃条恢复应 400");

		// 归档 → 恢复 → 200（status 回 1）
		Memory row = memoryService.getById(vo.getId());
		row.setStatus(2);
		memoryService.updateById(row);
		MemoryVO restored = memoryService.restore(agent.getId(), vo.getId());
		assertEquals(1, restored.getStatus(), "归档条恢复后应回 1");

		// 被取代条（3）恢复 → 400
		MemoryVO v2 = memoryService.create(agent.getId(), req("preference", "self", "偏好v2", "内容v2", null));
		MemorySupersedeReq sup = new MemorySupersedeReq();
		sup.setNewMemoryId(v2.getId());
		memoryService.supersede(agent.getId(), vo.getId(), sup);
		assertThrows(BadRequestException.class, () -> memoryService.restore(agent.getId(), vo.getId()),
				"被取代条恢复应 400");
	}

	// ------------------------------------------------------------
	// T_mem_08 检索：写入后可检索 / search 只做召回不改计数 / confirm 才 +1（M7.1-M4 价值闭环）
	// ------------------------------------------------------------
	@Test
	void testSearchValueLoop() {
		log("T_mem_08 检索召回与价值闭环");
		Agent agent = newAgent("mem08");
		MemoryVO vo = createIndexed(agent.getId(), "fact", "dialogue", "Redis 缓存键契约",
				"缓存键 ac:key:{keyHash}，TTL 30 秒", "redis,缓存");

		List<MemorySearchHitVO> hits = memoryService.search(agent.getId(), "Redis 缓存", 10);
		assertEquals(1, hits.size(), "写入并索引后应可检索");
		MemorySearchHitVO hit = hits.get(0);
		assertEquals(vo.getId(), hit.getId());
		assertEquals(1, hit.getStatus(), "活跃条检索带 status=1 标记");
		assertNull(hit.getLastAccessedAt(), "search 只做召回，未确认前 last_accessed_at 应为 null");

		// search 命中后 accessCount 不变（M7.1-M4：接触≠价值）
		Memory after = memoryService.getById(vo.getId());
		assertEquals(0, after.getAccessCount(), "search 命中不应改 access_count");
		assertNull(after.getLastAccessedAt(), "search 命中不应刷新 last_accessed_at");

		// 再命中一次：计数仍不变
		memoryService.search(agent.getId(), "Redis 缓存", 10);
		assertEquals(0, memoryService.getById(vo.getId()).getAccessCount(), "再次命中计数仍不变");

		// confirm → access_count+1 且 last_accessed_at 刷新（与 confirmed_at 同步写）
		memoryService.confirm(agent.getId(), vo.getId());
		Memory confirmed = memoryService.getById(vo.getId());
		assertEquals(1, confirmed.getAccessCount(), "confirm 后 access_count 应 +1");
		assertNotNull(confirmed.getLastAccessedAt(), "confirm 后 last_accessed_at 应刷新");
		assertNotNull(confirmed.getConfirmedAt(), "confirm 后 confirmed_at 应刷新");
		assertEquals(confirmed.getLastAccessedAt(), confirmed.getConfirmedAt(), "两者应同步写");

		// 空 q → 400
		assertThrows(BadRequestException.class, () -> memoryService.search(agent.getId(), " ", 10),
				"空 q 应 400");
	}

	// ------------------------------------------------------------
	// T_mem_09 Lucene：删除后不可检索 / 归档可检索带标记（任务书 §2.4）
	// ------------------------------------------------------------
	@Test
	void testSearchDeletedAndArchived() {
		log("T_mem_09 删除不可检索/归档可检索");
		Agent agent = newAgent("mem09");
		// 归档条：参与检索，带 status=2 标记
		MemoryVO archived = createIndexed(agent.getId(), "lesson", "self", "Kubernetes 网络排障",
				"CNI 插件冲突时先查 kube-proxy 日志", "k8s,排障");
		Memory row = memoryService.getById(archived.getId());
		row.setStatus(2);
		memoryService.updateById(row);

		List<MemorySearchHitVO> hits = memoryService.search(agent.getId(), "Kubernetes 排障", 10);
		assertEquals(1, hits.size(), "归档条应参与检索");
		assertEquals(2, hits.get(0).getStatus(), "归档条检索结果应带 status=2 标记");

		// 删除：移除索引文档 → 不可检索
		MemoryVO deleted = createIndexed(agent.getId(), "event", "system", "MinIO 迁移完成",
				"附件存储已迁移至 MinIO", "minio");
		memoryService.delete(agent.getId(), deleted.getId());
		memoryIndexManager.removeMemory(deleted.getId());
		List<MemorySearchHitVO> afterDelete = memoryService.search(agent.getId(), "MinIO 迁移", 10);
		assertEquals(0, afterDelete.size(), "删除后不可检索");
	}

	// ------------------------------------------------------------
	// T_mem_10 检索私域隔离：跨 agent 内容不命中（fail-closed）
	// ------------------------------------------------------------
	@Test
	void testSearchAgentIsolation() {
		log("T_mem_10 检索私域隔离");
		Agent owner = newAgent("mem10a");
		Agent other = newAgent("mem10b");
		createIndexed(owner.getId(), "fact", "self", "堡垒机通道配置", "SSH 桥接堡垒机通道", "堡垒机");

		// 其他 agent 检索同词 → 不命中（agentId MUST 过滤）
		List<MemorySearchHitVO> hits = memoryService.search(other.getId(), "堡垒机", 10);
		assertEquals(0, hits.size(), "跨 agent 检索不应命中");
		// owner 检索 → 命中
		assertEquals(1, memoryService.search(owner.getId(), "堡垒机", 10).size(), "owner 检索应命中");
	}

	// ------------------------------------------------------------
	// T_mem_11 同分 weight 排序：weight 高者前（任务书 §2.3）
	// ------------------------------------------------------------
	@Test
	void testWeightTieBreaker() {
		log("T_mem_11 同分 weight 排序");
		Agent agent = newAgent("mem11");
		String title = "权重排序探针条目";
		String content = "完全相同的正文内容用于构造同分";
		MemoryVO low = createIndexed(agent.getId(), "fact", "self", title, content, null);
		MemoryVO high = createIndexed(agent.getId(), "fact", "self", title, content, null);
		// high 权重置 50（模拟 cron 重算后的高分条）
		Memory highRow = memoryService.getById(high.getId());
		highRow.setWeight(50);
		memoryService.updateById(highRow);
		memoryIndexManager.indexMemory(high.getId(), agent.getId(), title, null, content, 50);

		List<MemorySearchHitVO> hits = memoryService.search(agent.getId(), "权重排序探针", 10);
		assertEquals(2, hits.size(), "两条同内容记忆都应命中");
		assertEquals(high.getId(), hits.get(0).getId(), "同分时 weight 高者应在前");
		assertEquals(low.getId(), hits.get(1).getId(), "同分时 weight 低者应在后");
	}

	// ------------------------------------------------------------
	// T_mem_12 分页筛选：status/memType/tag + update_time 倒序
	// ------------------------------------------------------------
	@Test
	void testPageFilters() {
		log("T_mem_12 分页筛选");
		Agent agent = newAgent("mem12");
		MemoryVO a = memoryService.create(agent.getId(), req("fact", "dialogue", "事实条", "内容", "标签A,共用"));
		MemoryVO b = memoryService.create(agent.getId(), req("lesson", "self", "教训条", "内容", "标签B,共用"));
		MemoryVO c = memoryService.create(agent.getId(), req("fact", "self", "事实条二", "内容", "标签C"));

		// memType 筛选
		var page = memoryService.pageOwn(agent.getId(), null, "fact", null, null, null, null, 1, 20);
		assertEquals(2, page.getTotal(), "memType=fact 应 2 条");
		// tag 精确筛选（FIND_IN_SET）
		page = memoryService.pageOwn(agent.getId(), null, null, "共用", null, null, null, 1, 20);
		assertEquals(2, page.getTotal(), "tag=共用 应 2 条（精确匹配逗号项）");
		page = memoryService.pageOwn(agent.getId(), null, null, "标签", null, null, null, 1, 20);
		assertEquals(0, page.getTotal(), "tag=标签 不应子串误匹配");
		// status 筛选
		Memory row = memoryService.getById(a.getId());
		row.setStatus(2);
		memoryService.updateById(row);
		page = memoryService.pageOwn(agent.getId(), 2, null, null, null, null, null, 1, 20);
		assertEquals(1, page.getTotal(), "status=2 应 1 条");
		// update_time 倒序（同毫秒内按 id 倒序兜底）
		page = memoryService.pageOwn(agent.getId(), null, null, null, null, null, null, 1, 20);
		var ids = page.getRecords().stream().map(MemoryVO::getId).toList();
		assertEquals(c.getId(), ids.get(0), "最新更新应在前");
		// 私域：其他 agent 列表为空
		assertEquals(0, memoryService.pageOwn(newAgent("mem12x").getId(), null, null, null, null, null, null, 1, 20).getTotal(),
				"其他 agent 列表应为空");
	}

	// ------------------------------------------------------------
	// T_mem_13 路由层：无 key 401 / scope 不足 403 / 有 scope 201（MockMvc）
	// ------------------------------------------------------------
	@Test
	void testApiAuthScopes() throws Exception {
		log("T_mem_13 路由层鉴权");
		Agent agent = newAgent("mem13");
		String body = "{\"memType\":\"fact\",\"source\":\"self\",\"title\":\"路由测试\",\"contentMd\":\"正文\"}";

		// 无 key → 401
		mockMvc.perform(post("/api/agent/memories")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());

		// 无效 key → 401
		mockMvc.perform(post("/api/agent/memories").header("X-API-Key", "ac_invalid_key")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());

		// scope 不足（只有 audit:read）→ 403
		String weakKey = apiKeyService.generateKey(agent.getId(), List.of("audit:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		mockMvc.perform(post("/api/agent/memories").header("X-API-Key", weakKey)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());

		// memory:write → 201
		String writeKey = apiKeyService.generateKey(agent.getId(), List.of("memory:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
		MvcResult created = mockMvc.perform(post("/api/agent/memories").header("X-API-Key", writeKey)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andReturn();
		Map<?, ?> data = (Map<?, ?>) objectMapper.readValue(
				created.getResponse().getContentAsString(), Map.class).get("data");
		Long memId = ((Number) data.get("id")).longValue();
		indexedIds.add(memId);

		// 只读 key 访问写端点 → 403；读端点 → 200
		String readKey = apiKeyService.generateKey(agent.getId(), List.of("memory:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		mockMvc.perform(post("/api/agent/memories").header("X-API-Key", readKey)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/agent/memories").header("X-API-Key", readKey))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/agent/memories/" + memId).header("X-API-Key", readKey))
				.andExpect(status().isOk());

		// 跨 agent：其他 agent 的 key 读 owner 的记忆 → 404（不泄露存在性）
		Agent other = newAgent("mem13x");
		String otherKey = apiKeyService.generateKey(other.getId(), List.of("memory:read", "memory:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
		mockMvc.perform(get("/api/agent/memories/" + memId).header("X-API-Key", otherKey))
				.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------
	// T_mem_14 路由层：参数校验 400（memType 白名单外）
	// ------------------------------------------------------------
	@Test
	void testApiValidation400() throws Exception {
		log("T_mem_14 路由层参数校验");
		Agent agent = newAgent("mem14");
		String key = apiKeyService.generateKey(agent.getId(), List.of("memory:write"),
				new Date(System.currentTimeMillis() + 3600_000L));
		String badBody = "{\"memType\":\"wrong\",\"source\":\"self\",\"title\":\"t\",\"contentMd\":\"c\"}";
		mockMvc.perform(post("/api/agent/memories").header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(badBody))
				.andExpect(status().isBadRequest());
	}

	// ------------------------------------------------------------
	// T_mem_15 门户分页（M7.1-M3）：匿名只读 / 仅 status=1 / weight DESC / 分页 count / 404 / 空页
	// ------------------------------------------------------------
	@Test
	void testPortalPage() throws Exception {
		log("T_mem_15 门户记忆分页");
		Agent agent = newAgent("mem15");
		MemoryVO low = createIndexed(agent.getId(), "fact", "self", "低权重", "低权重正文", null);
		MemoryVO mid = createIndexed(agent.getId(), "decision", "self", "中权重", "中权重正文", null);
		MemoryVO top = createIndexed(agent.getId(), "lesson", "self", "高权重", "高权重正文", null);
		MemoryVO archived = createIndexed(agent.getId(), "fact", "self", "已归档", "归档正文", null);
		MemoryVO supersededSrc = createIndexed(agent.getId(), "fact", "self", "被取代", "旧版正文", null);
		setWeight(top.getId(), 5);
		setWeight(mid.getId(), 3);
		setWeight(low.getId(), 1);
		memoryService.archiveByAdmin(archived.getId());
		MemorySupersedeReq sup = new MemorySupersedeReq();
		sup.setNewMemoryId(top.getId());
		memoryService.supersede(agent.getId(), supersededSrc.getId(), sup);

		// 匿名 GET → 200；仅 status=1；weight DESC 排序；count 只计活跃
		MvcResult result = mockMvc
				.perform(get("/api/portal/agents/" + agent.getId() + "/memories/page"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> data = (Map<?, ?>) objectMapper.readValue(
				result.getResponse().getContentAsString(), Map.class).get("data");
		List<?> list = (List<?>) data.get("list");
		assertEquals(3, ((Number) data.get("count")).intValue(), "count 应只计活跃记忆");
		assertEquals(3, list.size(), "列表应只含活跃记忆");
		Map<?, ?> first = (Map<?, ?>) list.get(0);
		assertEquals(top.getId().longValue(), ((Number) first.get("id")).longValue(), "weight 最高应在前");
		assertEquals("高权重", first.get("title"));
		assertNotNull(first.get("contentMd"), "门户条目应含 contentMd 全文");
		for (Object o : list) {
			String title = String.valueOf(((Map<?, ?>) o).get("title"));
			assertTrue(!"已归档".equals(title) && !"被取代".equals(title), "门户不应返回归档/被取代记忆");
		}

		// 分页：size=2 → 2 条 + count=3（总数不受 size 影响）
		MvcResult paged = mockMvc
				.perform(get("/api/portal/agents/" + agent.getId() + "/memories/page")
						.param("page", "1").param("size", "2"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> pdata = (Map<?, ?>) objectMapper.readValue(
				paged.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(3, ((Number) pdata.get("count")).intValue(), "count 应为活跃总数");
		assertEquals(2, ((List<?>) pdata.get("list")).size(), "size=2 应返回 2 条");

		// agent 不存在 → 404
		mockMvc.perform(get("/api/portal/agents/999999/memories/page"))
				.andExpect(status().isNotFound());

		// agent 存在但无记忆 → 空页（非 404）
		Agent empty = newAgent("mem15e");
		MvcResult emptyRes = mockMvc
				.perform(get("/api/portal/agents/" + empty.getId() + "/memories/page"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> edata = (Map<?, ?>) objectMapper.readValue(
				emptyRes.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(0, ((Number) edata.get("count")).intValue(), "无记忆 agent 应空页");
		assertTrue(((List<?>) edata.get("list")).isEmpty(), "无记忆 agent 列表应为空");
	}

	// ------------------------------------------------------------
	// T_mem_16 手动权重调整（M7.1-M4）：weight 覆盖落库 / null 不修改 / 只带 weight 不误伤 / 越界 400 / confirm 不破坏手动值
	// ------------------------------------------------------------
	@Test
	void testManualWeightUpdate() throws Exception {
		log("T_mem_16 手动权重调整");
		Agent agent = newAgent("mem16");
		MemoryVO vo = memoryService.create(agent.getId(), req("fact", "self", "权重测试", "权重测试正文", null));
		assertEquals(Integer.valueOf(0), vo.getWeight(), "初始 weight=0");

		// 1) PUT 带 weight=80 → 覆盖落库值，详情 weight=80
		MemoryUpdateReq setW = new MemoryUpdateReq();
		setW.setWeight(80);
		MemoryVO updated = memoryService.update(agent.getId(), vo.getId(), setW);
		assertEquals(Integer.valueOf(80), updated.getWeight(), "手动 set weight 应覆盖落库值");
		assertEquals(Integer.valueOf(80), memoryService.detail(agent.getId(), vo.getId()).getMemory().getWeight(),
				"详情 weight 应为 80");

		// 2) PUT 不带 weight → weight 不变（null=不修改）
		MemoryUpdateReq noW = new MemoryUpdateReq();
		noW.setTitle("改标题不动权重");
		MemoryVO keepW = memoryService.update(agent.getId(), vo.getId(), noW);
		assertEquals("改标题不动权重", keepW.getTitle());
		assertEquals(Integer.valueOf(80), keepW.getWeight(), "不带 weight 时不应修改");

		// 3) 手动 set 后 confirm → weight 保持手动值（confirm 只刷 confirmed_at/access_count，weight 自动机制未被破坏）
		memoryService.confirm(agent.getId(), vo.getId());
		assertEquals(Integer.valueOf(80), memoryService.getById(vo.getId()).getWeight(),
				"confirm 不应改动手动设置的 weight");

		// 4) 路由层：weight=150 越界 → envelope code=400（@Max 校验，BindException 处理器既有行为）
		String key = apiKeyService.generateKey(agent.getId(), List.of("memory:write", "memory:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		MvcResult badRes = mockMvc.perform(put("/api/agent/memories/" + vo.getId())
				.header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"weight\":150}"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> badBody = objectMapper.readValue(badRes.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badBody.get("code")), "weight=150 越界应 code=400");
		assertEquals(Boolean.FALSE, badBody.get("success"), "越界请求应 success=false");

		// 5) 路由层：PUT 只带 weight=60 → 成功（「至少修改一项」不误伤）
		MvcResult okRes = mockMvc.perform(put("/api/agent/memories/" + vo.getId())
				.header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"weight\":60}"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> okBody = objectMapper.readValue(okRes.getResponse().getContentAsString(), Map.class);
		assertEquals("200", String.valueOf(okBody.get("code")), "只带 weight 应成功");
		assertEquals(60, ((Number) ((Map<?, ?>) okBody.get("data")).get("weight")).intValue(),
				"weight 应更新为 60");
	}

	// ------------------------------------------------------------
	// T_mem_17 sort=weight 排序（M7.1-M5）：weight DESC / 同 weight id DESC / 默认兼容
	// ------------------------------------------------------------
	@Test
	void testSortWeightOrder() {
		log("T_mem_17 sort=weight 排序 + 默认兼容");
		Agent agent = newAgent("mem17");
		MemoryVO w60 = memoryService.create(agent.getId(), req("fact", "self", "权重60", "内容", null));
		MemoryVO w50a = memoryService.create(agent.getId(), req("fact", "self", "权重50甲", "内容", null));
		MemoryVO w50b = memoryService.create(agent.getId(), req("fact", "self", "权重50乙", "内容", null));
		MemoryVO w30 = memoryService.create(agent.getId(), req("fact", "self", "权重30", "内容", null));
		setWeight(w60.getId(), 60);
		setWeight(w50a.getId(), 50);
		setWeight(w50b.getId(), 50);
		setWeight(w30.getId(), 30);

		// sort=weight：weight DESC，同 weight（50）按 id DESC（新记忆在前）
		var page = memoryService.pageOwn(agent.getId(), null, null, null,
				MemoryService.SORT_WEIGHT, null, null, 1, 20);
		assertEquals(List.of(w60.getId(), w50b.getId(), w50a.getId(), w30.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(),
				"sort=weight 应 weight DESC、同 weight id DESC");

		// 缺省（null）：update_time DESC, id DESC（现状默认，行为零变化）
		page = memoryService.pageOwn(agent.getId(), null, null, null, null, null, null, 1, 20);
		assertEquals(List.of(w30.getId(), w50b.getId(), w50a.getId(), w60.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(),
				"缺省 sort 应 update_time DESC、同毫秒 id DESC");

		// sort=updated 显式：与缺省一致
		page = memoryService.pageOwn(agent.getId(), null, null, null,
				MemoryService.SORT_UPDATED, null, null, 1, 20);
		assertEquals(List.of(w30.getId(), w50b.getId(), w50a.getId(), w60.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(),
				"sort=updated 应与缺省一致");
	}

	// ------------------------------------------------------------
	// T_mem_18 sort 非法值（M7.1-M5）：路由层 envelope code=400 + msg 提示；合法值回归 200
	// ------------------------------------------------------------
	@Test
	void testSortInvalid400() throws Exception {
		log("T_mem_18 sort 非法值 400");
		Agent agent = newAgent("mem18");
		String key = apiKeyService.generateKey(agent.getId(), List.of("memory:read"),
				new Date(System.currentTimeMillis() + 3600_000L));

		MvcResult badRes = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("sort", "abc"))
				.andExpect(status().isBadRequest())
				.andReturn();
		Map<?, ?> badBody = objectMapper.readValue(badRes.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badBody.get("code")), "sort=abc 应 envelope code=400");
		assertEquals(Boolean.FALSE, badBody.get("success"), "sort=abc 应 success=false");
		assertTrue(String.valueOf(badBody.get("msg")).contains("sort"), "msg 应含 sort 合法取值提示");

		// 合法值回归：weight / updated / 缺省 → 200
		mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key).param("sort", "weight"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key).param("sort", "updated"))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key))
				.andExpect(status().isOk());
	}

	// ------------------------------------------------------------
	// T_mem_19 since/until 时间范围筛选（M7.1-M5 追加）：筛 create_time / 含边界 / 独立与组合
	// ------------------------------------------------------------
	@Test
	void testSinceUntilFilter() {
		log("T_mem_19 since/until 时间范围筛选");
		Agent agent = newAgent("mem19");
		MemoryVO aug = memoryService.create(agent.getId(), req("fact", "self", "八月条", "内容", null));
		MemoryVO mid = memoryService.create(agent.getId(), req("fact", "self", "九月中旬条", "内容", null));
		MemoryVO late = memoryService.create(agent.getId(), req("fact", "self", "九月下旬条", "内容", null));
		touchCreateTime(aug.getId(), "2026-08-15 10:00:00");
		touchCreateTime(mid.getId(), "2026-09-15 12:00:00");
		touchCreateTime(late.getId(), "2026-09-20 08:00:00");

		// 区间 9 月：只含 mid/late（8 月排除）；区间内默认排序 id DESC
		var page = memoryService.pageOwn(agent.getId(), null, null, null, null,
				Timestamp.valueOf("2026-09-01 00:00:00"), Timestamp.valueOf("2026-09-30 23:59:59"), 1, 20);
		assertEquals(List.of(late.getId(), mid.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(), "9 月区间应 2 条");

		// since 独立：>= 9/15 00:00:00 → mid + late（边界当天含）
		page = memoryService.pageOwn(agent.getId(), null, null, null, null,
				Timestamp.valueOf("2026-09-15 00:00:00"), null, 1, 20);
		assertEquals(List.of(late.getId(), mid.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(), "since=9/15 应含边界当天");

		// until 独立：<= 9/15 23:59:59 → mid + aug（边界当天含）
		page = memoryService.pageOwn(agent.getId(), null, null, null, null,
				null, Timestamp.valueOf("2026-09-15 23:59:59"), 1, 20);
		assertEquals(List.of(mid.getId(), aug.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(), "until=9/15 应含边界当天");

		// 边界精确到秒：create_time=9/15 12:00:00 本身命中（>= 与 <= 均含）
		page = memoryService.pageOwn(agent.getId(), null, null, null, null,
				Timestamp.valueOf("2026-09-15 12:00:00"), Timestamp.valueOf("2026-09-15 12:00:00"), 1, 20);
		assertEquals(List.of(mid.getId()), page.getRecords().stream().map(MemoryVO::getId).toList(),
				"边界秒应双向含");

		// 组合：since/until + memType + sort=weight
		setWeight(mid.getId(), 40);
		setWeight(late.getId(), 30);
		page = memoryService.pageOwn(agent.getId(), null, "fact", null,
				MemoryService.SORT_WEIGHT,
				Timestamp.valueOf("2026-09-01 00:00:00"), Timestamp.valueOf("2026-09-30 23:59:59"), 1, 20);
		assertEquals(List.of(mid.getId(), late.getId()),
				page.getRecords().stream().map(MemoryVO::getId).toList(),
				"筛选+排序+时间范围组合应正常");
	}

	// ------------------------------------------------------------
	// T_mem_20 since/until 路由层（M7.1-M5 追加）：yyyy-MM-dd 边界展开 / datetime 形式 / 非法格式 400
	// ------------------------------------------------------------
	@Test
	void testSinceUntilApi() throws Exception {
		log("T_mem_20 since/until 路由层");
		Agent agent = newAgent("mem20");
		MemoryVO mid = memoryService.create(agent.getId(), req("fact", "self", "九月十五条", "内容", null));
		touchCreateTime(mid.getId(), "2026-09-15 12:00:00");
		String key = apiKeyService.generateKey(agent.getId(), List.of("memory:read"),
				new Date(System.currentTimeMillis() + 3600_000L));

		// yyyy-MM-dd 形式：since=2026-09-15 展开为当天 00:00:00 → 含当天 12:00 的记忆
		MvcResult res = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("since", "2026-09-15"))
				.andExpect(status().isOk()).andReturn();
		Map<?, ?> data = (Map<?, ?>) objectMapper.readValue(
				res.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(1, ((Number) data.get("count")).intValue(), "since=date 形式应含边界当天");

		// yyyy-MM-dd 形式：until=2026-09-15 展开为当天 23:59:59 → 含
		res = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("until", "2026-09-15"))
				.andExpect(status().isOk()).andReturn();
		data = (Map<?, ?>) objectMapper.readValue(
				res.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(1, ((Number) data.get("count")).intValue(), "until=date 形式应含边界当天");

		// 区间外：since=2026-09-16 → 空
		res = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("since", "2026-09-16"))
				.andExpect(status().isOk()).andReturn();
		data = (Map<?, ?>) objectMapper.readValue(
				res.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(0, ((Number) data.get("count")).intValue(), "区间外应为空");

		// yyyy-MM-dd HH:mm:ss 形式：200 且命中
		res = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("since", "2026-09-15 00:00:00").param("until", "2026-09-15 23:59:59"))
				.andExpect(status().isOk()).andReturn();
		data = (Map<?, ?>) objectMapper.readValue(
				res.getResponse().getContentAsString(), Map.class).get("data");
		assertEquals(1, ((Number) data.get("count")).intValue(), "datetime 形式应命中");

		// 非法格式 → 400 + msg 指明参数名
		MvcResult badRes = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("since", "2026/08/01"))
				.andExpect(status().isBadRequest()).andReturn();
		Map<?, ?> badBody = objectMapper.readValue(badRes.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badBody.get("code")), "since 非法格式应 code=400");
		assertTrue(String.valueOf(badBody.get("msg")).contains("since"), "msg 应指明 since 格式");

		badRes = mockMvc.perform(get("/api/agent/memories").header("X-API-Key", key)
				.param("until", "2026-08-01 10:00"))
				.andExpect(status().isBadRequest()).andReturn();
		badBody = objectMapper.readValue(badRes.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badBody.get("code")), "until 非法格式应 code=400");
		assertTrue(String.valueOf(badBody.get("msg")).contains("until"), "msg 应指明 until 格式");
	}

	// ------------------------------------------------------------
	// T_mem_21 weight 保底线（M7.1-M3a）：POST/PUT 支持 + GET 回带 + decay max + 越界 400
	// ------------------------------------------------------------
	@Test
	void testWeightFloor() throws Exception {
		log("T_mem_21 weight 保底线");
		Agent agent = newAgent("mem21");

		// 1) POST 带 weightFloor=70 → 落库 70；不带 → 默认 0
		MemoryCreateReq withFloor = req("fact", "self", "指针条保底", "指针条正文", null);
		withFloor.setWeightFloor(70);
		MemoryVO floored = memoryService.create(agent.getId(), withFloor);
		assertEquals(Integer.valueOf(70), floored.getWeightFloor(), "POST weightFloor=70 应落库");
		MemoryVO plain = memoryService.create(agent.getId(), req("fact", "self", "普通条", "普通正文", null));
		assertEquals(Integer.valueOf(0), plain.getWeightFloor(), "不带 weightFloor 默认 0");

		// 2) decay 重算：floor=70、公式值 10（self 无访问无近期）→ 70；floor=0 → 公式值 10（回归）
		memoryDecayJob.execute(null);
		assertEquals(Integer.valueOf(70), memoryService.getById(floored.getId()).getWeight(),
				"衰减重算应取 max(公式值, floor)");
		assertEquals(Integer.valueOf(10), memoryService.getById(plain.getId()).getWeight(),
				"floor=0 行为与既有公式一致");

		// 3) 公式更高：floor=10 + dialogue + access=3 + 近期 confirm（公式 30+20+20=70）→ 70
		MemoryCreateReq highReq = req("fact", "dialogue", "高频条", "高频正文", null);
		highReq.setWeightFloor(10);
		MemoryVO high = memoryService.create(agent.getId(), highReq);
		Memory highRow = memoryService.getById(high.getId());
		highRow.setAccessCount(3);
		highRow.setConfirmedAt(new Date());
		memoryService.updateById(highRow);
		memoryDecayJob.execute(null);
		assertEquals(Integer.valueOf(70), memoryService.getById(high.getId()).getWeight(),
				"公式值(70)高于 floor(10) 应取公式值");

		// 4) PUT 改 weightFloor=50 → 生效；GET 列表/详情回带
		MemoryUpdateReq upd = new MemoryUpdateReq();
		upd.setWeightFloor(50);
		MemoryVO updated = memoryService.update(agent.getId(), floored.getId(), upd);
		assertEquals(Integer.valueOf(50), updated.getWeightFloor(), "PUT weightFloor=50 应生效");
		assertEquals(Integer.valueOf(50),
				memoryService.detail(agent.getId(), floored.getId()).getMemory().getWeightFloor(),
				"详情应回带 weightFloor");
		var page = memoryService.pageOwn(agent.getId(), null, null, null, null, null, null, 1, 20);
		assertTrue(page.getRecords().stream().allMatch(v -> v.getWeightFloor() != null),
				"列表 VO 应回带 weightFloor");

		// 5) 路由层：POST/PUT weightFloor=101 → envelope code=400（@Max 校验，BindException 处理器既有行为，与 weight=150 同款）
		String key = apiKeyService.generateKey(agent.getId(), List.of("memory:write", "memory:read"),
				new Date(System.currentTimeMillis() + 3600_000L));
		MvcResult badPost = mockMvc.perform(post("/api/agent/memories")
				.header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"memType\":\"fact\",\"source\":\"self\",\"title\":\"t\",\"contentMd\":\"c\",\"weightFloor\":101}"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> badPostBody = objectMapper.readValue(badPost.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badPostBody.get("code")), "POST weightFloor=101 应 code=400");

		MvcResult badPut = mockMvc.perform(put("/api/agent/memories/" + floored.getId())
				.header("X-API-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"weightFloor\":101}"))
				.andExpect(status().isOk())
				.andReturn();
		Map<?, ?> badPutBody = objectMapper.readValue(badPut.getResponse().getContentAsString(), Map.class);
		assertEquals("400", String.valueOf(badPutBody.get("code")), "PUT weightFloor=101 应 code=400");
	}

	/** 调整记忆 weight（门户排序断言辅助） */
	private void setWeight(Long id, int weight) {
		Memory row = memoryService.getById(id);
		row.setWeight(weight);
		memoryService.updateById(row);
	}

	/** 改写记忆 create_time（since/until 筛选断言辅助；update_time 不动） */
	private void touchCreateTime(Long id, String ts) {
		Memory row = memoryService.getById(id);
		row.setCreateTime(Timestamp.valueOf(ts));
		memoryService.updateById(row);
	}

	private void log(String title) {
		System.out.println("========== " + title + " ==========");
	}
}
