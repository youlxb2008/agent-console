package com.youlxb2008.admin.modules.agent.memory.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryCreateReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemorySupersedeReq;
import com.youlxb2008.admin.modules.agent.memory.dto.MemoryUpdateReq;
import com.youlxb2008.admin.modules.agent.memory.entity.Memory;
import com.youlxb2008.admin.modules.agent.memory.event.MemoryIndexEvent;
import com.youlxb2008.admin.modules.agent.memory.manager.MemoryIndexManager;
import com.youlxb2008.admin.modules.agent.memory.mapper.MemoryMapper;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryDetailVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemorySearchHitVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryStatsVO;
import com.youlxb2008.admin.modules.agent.memory.vo.MemoryVO;
import com.youlxb2008.admin.modules.agent.memory.vo.PortalMemoryVO;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;

import lombok.extern.slf4j.Slf4j;

/**
 * 记忆 Service（M7.1，agent 私域记忆库核心逻辑）
 * <p>
 * 纯索引 CRUD（真相源即 t_memory 行，contentMd 直接入库，无文件系统）。
 * 核心语义（任务书 §2.1/§2.2）：
 * <ul>
 *   <li>归属：<b>跨 agent 访问一律 404（fail-closed，不泄露存在性）</b>——与 todo 的
 *       越权 403 刻意不同，任务书硬性要求对齐 KB 现有做法</li>
 *   <li>生命周期：status 1=active / 2=archived / 3=superseded；
 *       supersede 旧条置 3 并记 superseded_by；restore 仅 archived（2→1）可恢复</li>
 *   <li>检索：Lucene 独立索引（{@link MemoryIndexManager}），search 只做召回不改计数
 *       （M7.1-M4 价值闭环：access 严格=被确认有价值，confirm 时原子 +1）；
 *       归档/被取代参与检索带标记</li>
 *   <li>索引同步：写侧发布 {@link MemoryIndexEvent}，事务提交后异步消费
 *       （对齐 KnowledgeIndexService 事件机制）</li>
 *   <li>tags：逗号分隔字符串（split → trim → 去空 → 去重 → 重 join，≤512）</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryService extends ServiceImpl<MemoryMapper, Memory> {

	/** memType 白名单（任务书 §2.1：fact/decision/preference/lesson/event） */
	public static final Set<String> MEM_TYPES = Set.of("fact", "decision", "preference", "lesson", "event");

	/** source 白名单（任务书 §2.1：dialogue/self/system） */
	public static final Set<String> SOURCES = Set.of("dialogue", "self", "system");

	/** 生命周期：活跃 */
	public static final int STATUS_ACTIVE = 1;
	/** 生命周期：归档 */
	public static final int STATUS_ARCHIVED = 2;
	/** 生命周期：被取代 */
	public static final int STATUS_SUPERSEDED = 3;

	/** M7.1-M5：sort 取值白名单（Controller 校验，Service 收到即信任） */
	public static final String SORT_UPDATED = "updated";
	public static final String SORT_WEIGHT = "weight";

	/** title ≤255 字符（对齐表列 VARCHAR(255)） */
	private static final int MAX_TITLE_LEN = 255;
	/** tags ≤512 字符（对齐表列 VARCHAR(512)） */
	private static final int MAX_TAGS_LEN = 512;
	/** refUrl ≤512 字符（对齐表列 VARCHAR(512)） */
	private static final int MAX_REF_URL_LEN = 512;
	/** superseded 链回溯最大跳数（防脏数据成环死循环） */
	private static final int CHAIN_MAX_HOPS = 100;

	@Autowired
	private MemoryIndexManager memoryIndexManager;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	/** 管理端 agentName 回填用（M7.1 M2；AgentService 无反向依赖，无循环） */
	@Autowired
	private AgentService agentService;

	// ============================================================
	// 定位 / 归属校验（fail-closed）
	// ============================================================

	/**
	 * 私域归属校验：记忆须存在且归属当前 agent。
	 * <p>
	 * 任务书硬性要求：跨 agent 访问一律 <b>404</b>（不泄露存在性，对齐 KB 现有做法）；
	 * 不存在/已软删/非本人 → 统一 404。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 * @return 记忆行（已确认归属）
	 */
	public Memory requireOwned(Long agentId, Long id) {
		// getById 由 @TableLogic 自动过滤 is_deleted=1
		Memory memory = getById(id);
		if (memory == null || !agentId.equals(memory.getAgentId())) {
			throw new NotFoundException("记忆不存在");
		}
		return memory;
	}

	// ============================================================
	// 写操作
	// ============================================================

	/**
	 * 写入记忆（POST /api/agent/memories）：校验白名单 → 落行（status=1/weight=0）→ 发布索引事件。
	 *
	 * @param agentId 当前 agent id
	 * @param req     写入请求
	 * @return VO
	 */
	public MemoryVO create(Long agentId, MemoryCreateReq req) {
		Memory memory = createEntity(agentId, req);
		eventPublisher.publishEvent(MemoryIndexEvent.save(memory.getId()));
		return toVO(memory);
	}

	/**
	 * 更新记忆（PUT /api/agent/memories/{id}）：全字段可选（null=不修改），至少修改一项。
	 * memType 传入时仍走白名单校验；weight 传入时手动覆盖落库值（M7.1-M4），
	 * 后续 access 加成 / memoryDecayJob 衰减照常在新值上作用（自动机制零改动）；
	 * weightFloor 为权重保底线（M7.1-M3a：null=不修改，0-100）。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 * @param req     更新请求
	 * @return VO
	 */
	public MemoryVO update(Long agentId, Long id, MemoryUpdateReq req) {
		Memory memory = requireOwned(agentId, id);
		boolean any = false;
		if (req.getTitle() != null) {
			validateTitle(req.getTitle());
			memory.setTitle(req.getTitle().trim());
			any = true;
		}
		if (req.getContentMd() != null) {
			if (req.getContentMd().isBlank()) {
				throw new BadRequestException("contentMd 不能为空");
			}
			memory.setContentMd(req.getContentMd());
			any = true;
		}
		if (req.getMemType() != null) {
			validateMemType(req.getMemType());
			memory.setMemType(req.getMemType().trim());
			any = true;
		}
		if (req.getTags() != null) {
			memory.setTags(normalizeTags(req.getTags()));
			any = true;
		}
		if (req.getRefUrl() != null) {
			validateRefUrl(req.getRefUrl());
			memory.setRefUrl(req.getRefUrl());
			any = true;
		}
		if (req.getWeight() != null) {
			memory.setWeight(req.getWeight());
			any = true;
		}
		if (req.getWeightFloor() != null) {
			memory.setWeightFloor(req.getWeightFloor());
			any = true;
		}
		if (!any) {
			throw new BadRequestException("至少修改一项（title/contentMd/memType/tags/refUrl/weight/weightFloor）");
		}
		memory.setUpdateTime(new Date());
		updateById(memory);

		eventPublisher.publishEvent(MemoryIndexEvent.save(memory.getId()));
		return toVO(getById(id));
	}

	/**
	 * 确认记忆（POST /api/agent/memories/{id}/confirm）：confirmed_at=now，
	 * 同时 access_count 原子 +1、last_accessed_at=now（M7.1-M4 价值闭环：access=被确认有价值）。
	 * weight 由 memoryDecayJob 全量重算（判断逻辑不动）。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 */
	public void confirm(Long agentId, Long id) {
		requireOwned(agentId, id);
		Date now = new Date();
		// M7.1-M4 价值闭环：confirm 才算「被确认有价值」——access_count 原子 +1，
		// last_accessed_at 与 confirmed_at 同步刷新（search 命中不再计数）
		update(new LambdaUpdateWrapper<Memory>()
				.eq(Memory::getId, id)
				.set(Memory::getConfirmedAt, now)
				.set(Memory::getLastAccessedAt, now)
				.setSql("access_count = access_count + 1")
				.set(Memory::getUpdateTime, now));
	}

	/**
	 * 取代记忆（POST /api/agent/memories/{id}/supersede）：旧条 status=3 + superseded_by=新 id。
	 * <p>
	 * 二选一按 body 内容判断（任务书 §2.2）：
	 * <ul>
	 *   <li>newMemoryId 非空 → 关联已有新条（须存在/属于本人/status=1，且不能是本条自己）</li>
	 *   <li>否则带新记忆字段 → 创建新条并关联（字段校验同 create）</li>
	 * </ul>
	 * 两步写入同事务（创建/更新失败整体回滚）；新条索引事件在事务提交后消费。
	 *
	 * @param agentId 当前 agent id
	 * @param id      被取代的旧记忆 id
	 * @param req     取代请求
	 * @return 新条 VO（关联已有或新建）
	 */
	@org.springframework.transaction.annotation.Transactional
	public MemoryVO supersede(Long agentId, Long id, MemorySupersedeReq req) {
		Memory old = requireOwned(agentId, id);
		Memory newMemory;
		if (req.getNewMemoryId() != null) {
			// 路径 A：关联已有新条
			if (req.getNewMemoryId().equals(id)) {
				throw new BadRequestException("不能以自己取代自己");
			}
			newMemory = requireOwned(agentId, req.getNewMemoryId());
			if (newMemory.getStatus() == null || newMemory.getStatus() != STATUS_ACTIVE) {
				throw new BadRequestException("新记忆须为活跃状态（status=1）");
			}
		} else if (req.isCreateNew()) {
			// 路径 B：创建新条并关联
			MemoryCreateReq createReq = new MemoryCreateReq();
			createReq.setMemType(req.getMemType());
			createReq.setSource(req.getSource());
			createReq.setTitle(req.getTitle());
			createReq.setContentMd(req.getContentMd());
			createReq.setTags(req.getTags());
			createReq.setRefUrl(req.getRefUrl());
			newMemory = createEntity(agentId, createReq);
		} else {
			throw new BadRequestException("body 须带 newMemoryId 或新记忆字段（title/contentMd）");
		}

		// 旧条置被取代 + 指向新条（链式：沿 superseded_by 可回溯最新版）
		old.setStatus(STATUS_SUPERSEDED);
		old.setSupersededBy(newMemory.getId());
		old.setUpdateTime(new Date());
		updateById(old);

		// 新条（路径 B 新建时）也要进索引；路径 A 新条已在库（索引已存在，SAVE 幂等覆写无害）
		eventPublisher.publishEvent(MemoryIndexEvent.save(newMemory.getId()));
		return toVO(newMemory);
	}

	/**
	 * 恢复记忆（POST /api/agent/memories/{id}/restore）：status 2→1（仅 archived 可恢复）。
	 * 被取代（3）不可直接恢复——须先处理取代链（任务书 §2.2）。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 * @return VO
	 */
	public MemoryVO restore(Long agentId, Long id) {
		Memory memory = requireOwned(agentId, id);
		if (memory.getStatus() == null || memory.getStatus() != STATUS_ARCHIVED) {
			throw new BadRequestException("仅归档状态（status=2）可恢复");
		}
		memory.setStatus(STATUS_ACTIVE);
		memory.setUpdateTime(new Date());
		updateById(memory);

		eventPublisher.publishEvent(MemoryIndexEvent.save(memory.getId()));
		return toVO(memory);
	}

	/**
	 * 删除记忆（DELETE /api/agent/memories/{id}）：逻辑删除（is_deleted=1）+ 移除索引文档。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 */
	public void delete(Long agentId, Long id) {
		Memory memory = requireOwned(agentId, id);
		removeById(memory.getId());
		eventPublisher.publishEvent(MemoryIndexEvent.delete(memory.getId()));
	}

	// ============================================================
	// 读操作
	// ============================================================

	/**
	 * 自己的记忆分页（GET /api/agent/memories）：status/memType/tag/since/until 筛选 + sort 排序。
	 * tag 筛选用 CONCAT+LIKE 精确匹配逗号分隔项（FIND_IN_SET 在 H2 不存在，CONCAT+LIKE H2/MySQL 5.7 通用）。
	 * <p>
	 * M7.1-M5：sort=weight 时 weight DESC, id DESC（同权重新记忆在前，与门户 portalPageByAgent 口径一致）；
	 * 缺省/updated 维持 update_time DESC, id DESC（现状默认，行为零变化）。
	 * M7.1-M5 追加：since/until 筛 create_time（记忆产生时点；边界展开已在 Controller 完成，收到即信任）。
	 *
	 * @param agentId  当前 agent id
	 * @param status   生命周期筛选（可空）
	 * @param memType  类型筛选（可空）
	 * @param tag      标签筛选（可空，精确匹配单个标签项）
	 * @param sort     排序：SORT_WEIGHT=weight DESC,id DESC；其他（含 null/updated）=update_time DESC,id DESC
	 * @param since    create_time 下界（可空，含边界）
	 * @param until    create_time 上界（可空，含边界）
	 * @param page     页码（1 起）
	 * @param pageSize 每页条数
	 * @return 分页结果
	 */
	public IPage<MemoryVO> pageOwn(Long agentId, Integer status, String memType, String tag,
			String sort, Date since, Date until, long page, long pageSize) {
		// tag 判空须先于 apply 参数求值（MP apply 条件为 false 时参数表达式仍会求值，null.trim() NPE）
		boolean hasTag = tag != null && !tag.isBlank();
		var wrapper = Wrappers.<Memory>lambdaQuery()
				.eq(Memory::getAgentId, agentId)
				.eq(status != null, Memory::getStatus, status)
				.eq(memType != null && !memType.isBlank(), Memory::getMemType, memType)
				// M7.1-M5 追加：since/until 筛 create_time（记忆产生即事情发生时点，不用 update_time）
				.ge(since != null, Memory::getCreateTime, since)
				.le(until != null, Memory::getCreateTime, until);
		if (SORT_WEIGHT.equals(sort)) {
			// M7.1-M5：按重要程度排序，同 weight 时 id DESC（新记忆在前）
			wrapper.orderByDesc(Memory::getWeight)
					.orderByDesc(Memory::getId);
		} else {
			// 缺省/updated：update_time DESC, id DESC（现状默认）
			wrapper.orderByDesc(Memory::getUpdateTime)
					.orderByDesc(Memory::getId);
		}
		if (hasTag) {
			// FIND_IN_SET 在 H2（MODE=MySQL）不存在，改用 CONCAT+LIKE 精确匹配逗号项（H2/MySQL 5.7 通用）
			wrapper.apply("CONCAT(',', tags, ',') LIKE CONCAT('%,', {0}, ',%')", tag.trim());
		}
		IPage<Memory> result = page(new Page<>(page, pageSize), wrapper);
		return result.convert(this::toVO);
	}

	// ============================================================
	// 门户（M7.1-M3：/api/portal/agents/{agentId}/memories/page，匿名只读）
	// ============================================================

	/**
	 * 门户分页（GET /api/portal/agents/{agentId}/memories/page）：
	 * 仅活跃（status=1）记忆，排序 weight DESC, id DESC（同权重新记忆在前）。
	 * agent 不存在 → 404（与 ledger/page 一致）；存在但无记忆 → 空页（非 404）。
	 * 复用 {@link #STATUS_ACTIVE} 状态常量，不重写状态逻辑。
	 *
	 * @param agentId agent id
	 * @param page    页码（1 起）
	 * @param size    每页条数（Controller 已钳制）
	 * @return 分页结果（门户白名单 VO，含 contentMd 全文）
	 */
	public IPage<PortalMemoryVO> portalPageByAgent(Long agentId, long page, long size) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		var wrapper = Wrappers.<Memory>lambdaQuery()
				.eq(Memory::getAgentId, agentId)
				.eq(Memory::getStatus, STATUS_ACTIVE)
				.orderByDesc(Memory::getWeight)
				.orderByDesc(Memory::getId);
		IPage<Memory> result = page(new Page<>(page, size), wrapper);
		return result.convert(this::toPortalVO);
	}

	/** entity → 门户 VO（白名单字段，含 contentMd 全文） */
	private PortalMemoryVO toPortalVO(Memory memory) {
		PortalMemoryVO vo = new PortalMemoryVO();
		vo.setId(memory.getId());
		vo.setMemType(memory.getMemType());
		vo.setTitle(memory.getTitle());
		vo.setContentMd(memory.getContentMd());
		vo.setTags(memory.getTags());
		vo.setSource(memory.getSource());
		vo.setWeight(memory.getWeight());
		vo.setAccessCount(memory.getAccessCount());
		vo.setCreateTime(memory.getCreateTime());
		vo.setUpdateTime(memory.getUpdateTime());
		return vo;
	}

	/**
	 * 记忆详情（GET /api/agent/memories/{id}）：含 superseded 链回溯——
	 * 本条被取代（status=3）时沿 superseded_by 向前找链尾最新版。
	 *
	 * @param agentId 当前 agent id
	 * @param id      记忆 id
	 * @return 详情（本条 + 链途经节点 + 链尾最新版）
	 */
	public MemoryDetailVO detail(Long agentId, Long id) {
		Memory memory = requireOwned(agentId, id);
		return buildDetail(memory);
	}

	/**
	 * 详情构建（agent 域 detail 与管理端 detailByAdmin 共用）：
	 * 含 superseded 链回溯——本条被取代（status=3）时沿 superseded_by 向前找链尾最新版。
	 */
	private MemoryDetailVO buildDetail(Memory memory) {
		Long agentId = memory.getAgentId();
		Long id = memory.getId();
		MemoryDetailVO vo = new MemoryDetailVO();
		vo.setMemory(toVO(memory));
		vo.setContentMd(memory.getContentMd());

		// 链回溯：status=3 且 superseded_by 非空 → 沿链找链尾（防环：visited + 最大跳数）
		List<MemoryDetailVO.ChainNode> chain = new ArrayList<>();
		chain.add(toChainNode(memory));
		Memory latest = memory;
		Set<Long> visited = new HashSet<>();
		visited.add(memory.getId());
		int hops = 0;
		while (latest.getStatus() != null && latest.getStatus() == STATUS_SUPERSEDED
				&& latest.getSupersededBy() != null && hops++ < CHAIN_MAX_HOPS) {
			Long nextId = latest.getSupersededBy();
			if (!visited.add(nextId)) {
				log.warn("superseded 链成环（脏数据），截断回溯：id={} 环入口={}", id, nextId);
				break;
			}
			Memory next = getById(nextId);
			// 链尾条目可能已被删除（逻辑删）：链在此截断，latest 保持当前
			if (next == null || !agentId.equals(next.getAgentId())) {
				break;
			}
			chain.add(toChainNode(next));
			latest = next;
		}
		vo.setChain(chain);
		vo.setLatest(latest.getId().equals(memory.getId()) ? null : toVO(latest));
		return vo;
	}

	/**
	 * Lucene 检索（GET /api/agent/memories/search）：只做召回，不改 access_count/last_accessed_at。
	 * <p>
	 * M7.1-M4 价值闭环：access 必须代表「被确认有价值」，接触≠价值——计数只在 confirm 时 +1。
	 * 流程：Lucene 私域检索（agentId MUST 过滤）→ DB 批查（is_deleted=0 双保险）→
	 * 按 Lucene 顺序输出 → 返回带 status 标记的命中列表。
	 * 归档/被取代条目参与检索但带 status（任务书 §2.3）。
	 *
	 * @param agentId 当前 agent id
	 * @param q       查询词
	 * @param limit   返回条数（默认 10，上限 30）
	 * @return 命中列表（相关度降序，同分 weight 高者前）
	 */
	public List<MemorySearchHitVO> search(Long agentId, String q, int limit) {
		if (q == null || q.isBlank()) {
			throw new BadRequestException("q 不能为空");
		}
		int topN = Math.min(Math.max(limit, 1), 30);
		List<MemoryIndexManager.MemoryHit> hits = memoryIndexManager.search(q, agentId, topN);
		if (hits.isEmpty()) {
			return List.of();
		}

		// DB 批查（is_deleted=0 由 @TableLogic 自动过滤；agent_id 再守卫一层 fail-closed）
		List<Long> ids = hits.stream().map(MemoryIndexManager.MemoryHit::getId).toList();
		Map<Long, Memory> rowMap = listByIds(ids).stream()
				.filter(m -> agentId.equals(m.getAgentId()))
				.collect(Collectors.toMap(Memory::getId, Function.identity()));

		List<MemorySearchHitVO> result = new ArrayList<>(hits.size());
		for (MemoryIndexManager.MemoryHit hit : hits) {
			Memory memory = rowMap.get(hit.getId());
			if (memory == null) {
				// 索引残留（删除事件丢失等）：跳过，不回传
				continue;
			}
			MemorySearchHitVO vo = new MemorySearchHitVO();
			vo.setId(memory.getId());
			vo.setMemType(memory.getMemType());
			vo.setSource(memory.getSource());
			vo.setTitle(memory.getTitle());
			vo.setContentMd(memory.getContentMd());
			vo.setTags(memory.getTags());
			vo.setRefUrl(memory.getRefUrl());
			vo.setStatus(memory.getStatus());
			vo.setSupersededBy(memory.getSupersededBy());
			vo.setWeight(memory.getWeight());
			vo.setScore(hit.getScore());
			vo.setLastAccessedAt(memory.getLastAccessedAt());
			result.add(vo);
		}
		return result;
	}

	// ============================================================
	// 内部实现
	// ============================================================

	/** 落行（create 与 supersede 路径 B 共用；调用方负责发布索引事件） */
	private Memory createEntity(Long agentId, MemoryCreateReq req) {
		validateMemType(req.getMemType());
		validateSource(req.getSource());
		validateTitle(req.getTitle());
		if (req.getContentMd() == null || req.getContentMd().isBlank()) {
			throw new BadRequestException("contentMd 不能为空");
		}
		validateRefUrl(req.getRefUrl());

		Memory memory = new Memory();
		memory.setAgentId(agentId);
		memory.setMemType(req.getMemType().trim());
		memory.setSource(req.getSource().trim());
		memory.setTitle(req.getTitle().trim());
		memory.setContentMd(req.getContentMd());
		memory.setTags(normalizeTags(req.getTags()));
		memory.setRefUrl(req.getRefUrl());
		memory.setStatus(STATUS_ACTIVE);
		memory.setWeight(0);
		memory.setWeightFloor(req.getWeightFloor() != null ? req.getWeightFloor() : 0);
		memory.setAccessCount(0);
		memory.setCreateTime(new Date());
		memory.setUpdateTime(new Date());
		save(memory);
		return memory;
	}

	/** memType 白名单校验（400） */
	private void validateMemType(String memType) {
		if (memType == null || !MEM_TYPES.contains(memType.trim())) {
			throw new BadRequestException("memType 非法（须为 fact/decision/preference/lesson/event）");
		}
	}

	/** source 白名单校验（400） */
	private void validateSource(String source) {
		if (source == null || !SOURCES.contains(source.trim())) {
			throw new BadRequestException("source 非法（须为 dialogue/self/system）");
		}
	}

	/** title 校验（非空 ≤255） */
	private void validateTitle(String title) {
		if (title == null || title.isBlank()) {
			throw new BadRequestException("title 不能为空");
		}
		if (title.trim().length() > MAX_TITLE_LEN) {
			throw new BadRequestException("title 最长 " + MAX_TITLE_LEN + " 字符");
		}
	}

	/** refUrl 校验（≤512） */
	private void validateRefUrl(String refUrl) {
		if (refUrl != null && refUrl.length() > MAX_REF_URL_LEN) {
			throw new BadRequestException("refUrl 最长 " + MAX_REF_URL_LEN + " 字符");
		}
	}

	/**
	 * tags 规范化：split → trim → 去空 → 去重（保序）→ 重 join（对齐 todo 惯例）
	 */
	private String normalizeTags(String tags) {
		if (tags == null || tags.isBlank()) {
			return null;
		}
		Set<String> parts = new LinkedHashSet<>();
		for (String part : tags.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) {
				parts.add(t);
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		String joined = String.join(",", parts);
		if (joined.length() > MAX_TAGS_LEN) {
			throw new BadRequestException("tags 最长 " + MAX_TAGS_LEN + " 字符");
		}
		return joined;
	}

	/** entity → VO */
	private MemoryVO toVO(Memory memory) {
		MemoryVO vo = new MemoryVO();
		vo.setId(memory.getId());
		vo.setAgentId(memory.getAgentId());
		vo.setMemType(memory.getMemType());
		vo.setSource(memory.getSource());
		vo.setTitle(memory.getTitle());
		vo.setTags(memory.getTags());
		vo.setRefUrl(memory.getRefUrl());
		vo.setStatus(memory.getStatus());
		vo.setSupersededBy(memory.getSupersededBy());
		vo.setWeight(memory.getWeight());
		vo.setWeightFloor(memory.getWeightFloor());
		vo.setAccessCount(memory.getAccessCount());
		vo.setLastAccessedAt(memory.getLastAccessedAt());
		vo.setConfirmedAt(memory.getConfirmedAt());
		vo.setCreateTime(memory.getCreateTime());
		vo.setUpdateTime(memory.getUpdateTime());
		return vo;
	}

	/** entity → 链节点摘要 */
	private MemoryDetailVO.ChainNode toChainNode(Memory memory) {
		MemoryDetailVO.ChainNode node = new MemoryDetailVO.ChainNode();
		node.setId(memory.getId());
		node.setTitle(memory.getTitle());
		node.setStatus(memory.getStatus());
		node.setSupersededBy(memory.getSupersededBy());
		return node;
	}

	// ============================================================
	// 管理端（M7.1 M2：/api/memory/admin/**，JWT + @RequiresPermissions）
	// 管理员全量可见，无私域归属校验（不存在统一 404）；
	// 写操作审计由 AuditLogAspect 自动记 t_sys_audit_log。
	// ============================================================

	/**
	 * 管理端分页（GET /api/memory/admin/agents/{agentId}/memories）：
	 * 指定 agent 的记忆分页（status/memType/keyword 筛选），回填 agentName。
	 *
	 * @param agentId agent id（Controller 已校验存在）
	 * @param status  生命周期筛选（可空）
	 * @param memType 类型筛选（可空）
	 * @param keyword 标题 LIKE 搜索（可空）
	 * @param page    页码（1 起）
	 * @param pageSize 每页条数
	 * @return 分页结果（VO 含 agentName）
	 */
	public IPage<MemoryVO> pageForAdmin(Long agentId, Integer status, String memType,
			String keyword, long page, long pageSize) {
		var wrapper = Wrappers.<Memory>lambdaQuery()
				.eq(Memory::getAgentId, agentId)
				.eq(status != null, Memory::getStatus, status)
				.eq(memType != null && !memType.isBlank(), Memory::getMemType, memType)
				.like(keyword != null && !keyword.isBlank(), Memory::getTitle, keyword)
				.orderByDesc(Memory::getUpdateTime)
				.orderByDesc(Memory::getId);
		IPage<Memory> result = page(new Page<>(page, pageSize), wrapper);
		return result.convert(this::toAdminVO);
	}

	/**
	 * 管理端统计（GET /api/memory/admin/memories/stats）：
	 * 各 agent 活跃/归档/被取代计数。内存分组（select 仅取两列，管理端量级可控），
	 * 避免 listMaps 在 H2/MySQL 下列名大小写差异。
	 *
	 * @return 按 agentId 升序的统计列表
	 */
	public List<MemoryStatsVO> statsForAdmin() {
		List<Memory> rows = list(Wrappers.<Memory>lambdaQuery()
				.select(Memory::getAgentId, Memory::getStatus));
		Map<Long, MemoryStatsVO> byAgent = new LinkedHashMap<>();
		for (Memory row : rows) {
			MemoryStatsVO vo = byAgent.computeIfAbsent(row.getAgentId(), this::newStatsVO);
			int status = row.getStatus() != null ? row.getStatus() : 0;
			if (status == STATUS_ACTIVE) {
				vo.setActiveCount(vo.getActiveCount() + 1);
			} else if (status == STATUS_ARCHIVED) {
				vo.setArchivedCount(vo.getArchivedCount() + 1);
			} else if (status == STATUS_SUPERSEDED) {
				vo.setSupersededCount(vo.getSupersededCount() + 1);
			}
			vo.setTotalCount(vo.getTotalCount() + 1);
		}
		return byAgent.values().stream()
				.sorted(Comparator.comparing(MemoryStatsVO::getAgentId))
				.toList();
	}

	/** 统计 VO 初始化（含 agentName 回填：display_name 优先回退 name） */
	private MemoryStatsVO newStatsVO(Long agentId) {
		MemoryStatsVO vo = new MemoryStatsVO();
		vo.setAgentId(agentId);
		Agent agent = agentService.getById(agentId);
		if (agent != null) {
			vo.setAgentName(agent.getDisplayName() != null && !agent.getDisplayName().isBlank()
					? agent.getDisplayName() : agent.getName());
		}
		return vo;
	}

	/**
	 * 管理端详情（GET /api/memory/admin/memories/{id}）：无归属校验，
	 * 链回溯逻辑与 agent 域 detail 一致（buildDetail 共用）。
	 */
	public MemoryDetailVO detailByAdmin(Long id) {
		Memory memory = getById(id);
		if (memory == null) {
			throw new NotFoundException("记忆不存在");
		}
		return buildDetail(memory);
	}

	/**
	 * 管理端归档（POST /api/memory/admin/memories/{id}/archive）：status 1→2。
	 * 仅活跃可归档（归档/被取代重复归档无意义，400）。
	 */
	public MemoryVO archiveByAdmin(Long id) {
		Memory memory = getById(id);
		if (memory == null) {
			throw new NotFoundException("记忆不存在");
		}
		if (memory.getStatus() == null || memory.getStatus() != STATUS_ACTIVE) {
			throw new BadRequestException("仅活跃状态（status=1）可归档");
		}
		memory.setStatus(STATUS_ARCHIVED);
		memory.setUpdateTime(new Date());
		updateById(memory);
		eventPublisher.publishEvent(MemoryIndexEvent.save(memory.getId()));
		return toVO(memory);
	}

	/**
	 * 管理端恢复（POST /api/memory/admin/memories/{id}/restore）：status 2→1。
	 * 仅归档可恢复（与 agent 域 restore 口径一致；被取代 3 不可直接恢复）。
	 */
	public MemoryVO restoreByAdmin(Long id) {
		Memory memory = getById(id);
		if (memory == null) {
			throw new NotFoundException("记忆不存在");
		}
		if (memory.getStatus() == null || memory.getStatus() != STATUS_ARCHIVED) {
			throw new BadRequestException("仅归档状态（status=2）可恢复");
		}
		memory.setStatus(STATUS_ACTIVE);
		memory.setUpdateTime(new Date());
		updateById(memory);
		eventPublisher.publishEvent(MemoryIndexEvent.save(memory.getId()));
		return toVO(memory);
	}

	/**
	 * 管理端删除（DELETE /api/memory/admin/memories/{id}）：
	 * 逻辑删除（is_deleted=1）+ 移除索引文档（与 agent 域 delete 口径一致）。
	 */
	public void deleteByAdmin(Long id) {
		Memory memory = getById(id);
		if (memory == null) {
			throw new NotFoundException("记忆不存在");
		}
		removeById(memory.getId());
		eventPublisher.publishEvent(MemoryIndexEvent.delete(memory.getId()));
	}

	/**
	 * 管理端调整权重保底线（M7.1-M3a，PUT /api/memory/admin/memories/{id}/weight-floor）：
	 * 校验 0-100（0=不保底）→ 落库刷新 update_time。
	 * weightFloor 不参与 Lucene 索引字段（title/tags/content_md），无需索引事件。
	 */
	public MemoryVO updateWeightFloorByAdmin(Long id, Integer weightFloor) {
		if (weightFloor == null || weightFloor < 0 || weightFloor > 100) {
			throw new BadRequestException("weightFloor 须在 0-100 内（0=不保底）");
		}
		Memory memory = getById(id);
		if (memory == null) {
			throw new NotFoundException("记忆不存在");
		}
		memory.setWeightFloor(weightFloor);
		memory.setUpdateTime(new Date());
		updateById(memory);
		return toVO(getById(id));
	}

	/** entity → VO（管理端版：回填 agentName） */
	private MemoryVO toAdminVO(Memory memory) {
		MemoryVO vo = toVO(memory);
		Agent agent = agentService.getById(memory.getAgentId());
		if (agent != null) {
			vo.setAgentName(agent.getDisplayName() != null && !agent.getDisplayName().isBlank()
					? agent.getDisplayName() : agent.getName());
		}
		return vo;
	}
}
