package com.youlxb2008.admin.modules.ledger.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.ledger.dto.LedgerCreateReq;
import com.youlxb2008.admin.modules.ledger.dto.LedgerPatchReq;
import com.youlxb2008.admin.modules.ledger.entity.Ledger;
import com.youlxb2008.admin.modules.ledger.manager.LedgerStorageManager;
import com.youlxb2008.admin.modules.ledger.mapper.LedgerMapper;
import com.youlxb2008.admin.modules.ledger.vo.LedgerDetailVO;
import com.youlxb2008.admin.modules.ledger.vo.LedgerVO;

/**
 * 台账索引 Service（M4.2，agent 私域流水）
 * <p>
 * 纯索引 CRUD 与查询族：lid 定位 / agent 归属校验（私域）/ 管理端分页 /
 * 门户只读；长正文 md 读写与 body 双轨切分委托 {@link LedgerStorageManager}。
 * 核心语义（契约 §1.4 + 任务书）：
 * <ul>
 *   <li>body 双轨：≤500 字存 body_inline；>500 字写 md 存 body_path；列表不读文件，详情读正文</li>
 *   <li>归属：agent_id 单值私域；非本人 PATCH/DELETE → 404（防枚举）</li>
 *   <li>软删：is_deleted=1（有 body_path 时文件保留不删）；全链路查询 is_deleted=0（fail-closed）</li>
 *   <li>排序：无 update_time 列，一律按 create_time 降序（流水按记账时间倒序）</li>
 * </ul>
 */
@Service
public class LedgerService extends ServiceImpl<LedgerMapper, Ledger> {

	/** 5 种类型（契约 §1.4）：progress/observation/decision/memo/session */
	public static final List<String> TYPES = List.of("progress", "observation", "decision", "memo", "session");

	@Autowired
	private LedgerStorageManager ledgerStorageManager;

	@Autowired
	private AgentService agentService;

	// ============================================================
	// 定位 / 归属校验
	// ============================================================

	/**
	 * 按 lid 查索引行（对外引用键，唯一；@TableLogic 自动过滤 is_deleted=1）
	 *
	 * @param lid 对外引用键（l_ + 10 位 base62）
	 * @return 索引行；不存在/格式非法返回 null
	 */
	public Ledger findByLid(String lid) {
		if (!ledgerStorageManager.isLidValid(lid)) {
			return null;
		}
		return getOne(Wrappers.<Ledger>lambdaQuery().eq(Ledger::getLid, lid), true);
	}

	/**
	 * 私域归属校验：台账须存在且归属当前 agent，否则统一 404（防枚举，同 M3.7/M4.1 语义）
	 *
	 * @param agentId 当前 agent id
	 * @param lid     台账 lid
	 * @return 索引行（已确认归属）
	 */
	public Ledger requireOwned(Long agentId, String lid) {
		Ledger row = findByLid(lid);
		if (row == null || !agentId.equals(row.getAgentId())) {
			throw new NotFoundException("台账不存在");
		}
		return row;
	}

	// ============================================================
	// agent 域 / 管理端 写操作
	// ============================================================

	/**
	 * 记一笔（契约 §2.4 POST）：type/title 必填 → body 双轨切分（≤500 字 inline / >500 字 md）→ 落索引
	 *
	 * @param agentId   归属 agent id
	 * @param agentName agent 名（审计 principal）
	 * @param req       创建请求（type/title 必填）
	 * @return 新建的索引行（含 lid）
	 */
	@Transactional(rollbackFor = Exception.class)
	public Ledger create(Long agentId, String agentName, LedgerCreateReq req) {
		String type = validateType(req.getType());
		String title = ledgerStorageManager.prepareTitle(req.getTitle());
		String lid = ledgerStorageManager.generateUniqueLid();
		// 1. body 双轨切分（>500 字先写 md 再落索引）
		String bodyInline = null;
		String bodyPath = null;
		if (ledgerStorageManager.shouldWriteFile(req.getBody())) {
			bodyPath = ledgerStorageManager.writeNewBody(agentId, lid, req.getBody());
		} else {
			bodyInline = req.getBody();
		}
		// 2. 落索引
		Ledger row = new Ledger();
		row.setLid(lid);
		row.setAgentId(agentId);
		row.setType(type);
		row.setTitle(title);
		row.setBodyInline(bodyInline);
		row.setBodyPath(bodyPath);
		row.setRefTaskId(req.getRefTaskId());
		row.setRefRequirementId(req.getRefRequirementId());
		row.setRefObjectiveId(req.getRefObjectiveId());
		row.setRefNoteNid(req.getRefNoteNid());
		row.setCreateTime(new Date());
		save(row);
		return row;
	}

	/**
	 * 部分更新（契约 §2.4 PATCH）：findByLid + 私域归属校验 → 至少一项校验 → 更新字段 →
	 * 若改 body 按双轨重新切分（inline↔md 互转；原 md 文件保留不删）。
	 *
	 * @param agentId 当前 agent id
	 * @param lid     台账 lid
	 * @param req     PATCH 请求（全空 → 400）
	 * @return 更新后索引行
	 */
	@Transactional(rollbackFor = Exception.class)
	public Ledger patchMeta(Long agentId, String lid, LedgerPatchReq req) {
		Ledger row = requireOwned(agentId, lid);
		return doPatch(row, req);
	}

	/**
	 * 管理端代改（LedgerAdminController PATCH）：无私域归属校验（管理员全量可见），仅台账存在（404）。
	 *
	 * @param lid 台账 lid
	 * @param req PATCH 请求（全空 → 400）
	 * @return 更新后索引行
	 */
	@Transactional(rollbackFor = Exception.class)
	public Ledger patchMetaByAdmin(String lid, LedgerPatchReq req) {
		Ledger row = findByLid(lid);
		if (row == null) {
			throw new NotFoundException("台账不存在");
		}
		return doPatch(row, req);
	}

	/**
	 * PATCH 核心（findByLid 已确认存在）：至少一项校验 → 更新字段 → body 双轨重切分
	 */
	private Ledger doPatch(Ledger row, LedgerPatchReq req) {
		if (req == null || (req.getType() == null && req.getTitle() == null && req.getBody() == null
				&& req.getRefTaskId() == null && req.getRefRequirementId() == null
				&& req.getRefObjectiveId() == null && req.getRefNoteNid() == null)) {
			throw new BadRequestException("至少修改一项（type/title/body/ref 四列）");
		}
		Ledger update = new Ledger();
		update.setId(row.getId());
		if (req.getType() != null) {
			update.setType(validateType(req.getType()));
		}
		if (req.getTitle() != null) {
			update.setTitle(ledgerStorageManager.prepareTitle(req.getTitle()));
		}
		// body 提供（含空串）→ 双轨重切分：>500 字写/覆写 md；≤500 字（含空）回退 inline
		if (req.getBody() != null) {
			if (ledgerStorageManager.shouldWriteFile(req.getBody())) {
				String path;
				if (row.getBodyPath() != null && !row.getBodyPath().isBlank()) {
					// 长→长：覆写原文件（lid 不变，路径稳定；文件缺失会经 atomicWrite 重建）
					ledgerStorageManager.overwriteBody(row.getBodyPath(), req.getBody());
					path = row.getBodyPath();
				} else {
					// 短→长：新写 md
					path = ledgerStorageManager.writeNewBody(row.getAgentId(), row.getLid(), req.getBody());
				}
				update.setBodyInline(null);
				update.setBodyPath(path);
			} else {
				// 长→短 / 短→短：回退 inline（旧 md 文件保留不删）
				update.setBodyInline(req.getBody());
				update.setBodyPath(null);
			}
		}
		if (req.getRefTaskId() != null) {
			update.setRefTaskId(req.getRefTaskId());
		}
		if (req.getRefRequirementId() != null) {
			update.setRefRequirementId(req.getRefRequirementId());
		}
		if (req.getRefObjectiveId() != null) {
			update.setRefObjectiveId(req.getRefObjectiveId());
		}
		if (req.getRefNoteNid() != null) {
			update.setRefNoteNid(req.getRefNoteNid());
		}
		updateById(update);

		// body 双轨字段显式同步（MyBatis-Plus updateById 默认字段策略 NOT_NULL：null 字段不写入数据库；
		// 长→短回退 inline 时需清空 body_path、短→长时需清空 body_inline，须用 lambdaUpdate 显式 set 含 null）
		if (req.getBody() != null) {
			lambdaUpdate().eq(Ledger::getId, row.getId())
					.set(Ledger::getBodyInline, update.getBodyInline())
					.set(Ledger::getBodyPath, update.getBodyPath())
					.update();
		}

		// 回填内存实体供返回（避免再查库）
		if (update.getType() != null) {
			row.setType(update.getType());
		}
		if (update.getTitle() != null) {
			row.setTitle(update.getTitle());
		}
		// body 改动时双轨字段成对同步（长→短回退会同时清 bodyPath，文件保留）
		if (req.getBody() != null) {
			row.setBodyInline(update.getBodyInline());
			row.setBodyPath(update.getBodyPath());
		}
		if (update.getRefTaskId() != null) {
			row.setRefTaskId(update.getRefTaskId());
		}
		if (update.getRefRequirementId() != null) {
			row.setRefRequirementId(update.getRefRequirementId());
		}
		if (update.getRefObjectiveId() != null) {
			row.setRefObjectiveId(update.getRefObjectiveId());
		}
		if (update.getRefNoteNid() != null) {
			row.setRefNoteNid(update.getRefNoteNid());
		}
		// body 改动时同步内存实体（双轨字段成对更新）
		if (req.getBody() != null) {
			row.setBodyInline(update.getBodyInline());
			row.setBodyPath(update.getBodyPath());
		}
		return row;
	}

	/**
	 * 软删（契约 §2.4 DELETE）：findByLid + 私域归属校验 → is_deleted=1（有 body_path 时文件保留不删）。
	 * 幂等：已软删/不存在统一 404（防枚举）。
	 *
	 * @param agentId 当前 agent id
	 * @param lid     台账 lid
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long agentId, String lid) {
		requireOwned(agentId, lid);
		doDelete(lid);
	}

	/**
	 * 管理端软删（LedgerAdminController DELETE）：无私域归属校验，仅台账存在（404）。
	 *
	 * @param lid 台账 lid
	 */
	@Transactional(rollbackFor = Exception.class)
	public void deleteByAdmin(String lid) {
		if (findByLid(lid) == null) {
			throw new NotFoundException("台账不存在");
		}
		doDelete(lid);
	}

	/** 软删核心：is_deleted=1（md 保留）；更新失败（并发删除）抛 404 */
	private void doDelete(String lid) {
		boolean ok = lambdaUpdate().eq(Ledger::getLid, lid)
				.set(Ledger::getIsDeleted, 1)
				.update();
		if (!ok) {
			throw new NotFoundException("台账不存在");
		}
	}

	// ============================================================
	// 查询族
	// ============================================================

	/**
	 * agent 域：自己（当前 agent）的台账流（契约 §2.4，create_time 降序；type/ref 筛选）
	 *
	 * @param agentId            当前 agent id
	 * @param type               类型筛选（可选，null/空白=不过滤）
	 * @param refTaskId          关联任务 tid 筛选（可选）
	 * @param refRequirementId   关联需求 rid 筛选（可选）
	 * @return 台账列表（只读索引，不读文件）
	 */
	public List<LedgerVO> listOwn(Long agentId, String type, String refTaskId, String refRequirementId) {
		return listSorted(Wrappers.<Ledger>lambdaQuery()
				.eq(Ledger::getAgentId, agentId)
				.eq(type != null && !type.isBlank(), Ledger::getType, type)
				.eq(refTaskId != null && !refTaskId.isBlank(), Ledger::getRefTaskId, refTaskId)
				.eq(refRequirementId != null && !refRequirementId.isBlank(), Ledger::getRefRequirementId,
						refRequirementId))
				.stream().map(this::toVO).collect(Collectors.toList());
	}

	/**
	 * 某 agent 的全部台账（管理端 GET /agents/{agentId}/ledger；agent 不存在 → 404）
	 *
	 * @param agentId agent id
	 * @return 台账列表（含 agentName 回填）
	 */
	public List<LedgerVO> listByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		Map<Long, String> nameById = agentNameMap(List.of(agentId));
		return listSorted(Wrappers.<Ledger>lambdaQuery().eq(Ledger::getAgentId, agentId))
				.stream().map(l -> {
					LedgerVO vo = toVO(l);
					vo.setAgentId(agentId);
					vo.setAgentName(nameById.get(agentId));
					return vo;
				}).collect(Collectors.toList());
	}

	/**
	 * 管理端分页（契约 §2.4 GET /api/ledger/admin/ledger）：agentId/type 筛选
	 *
	 * @param page    页码（从 1 开始）
	 * @param size    每页条数
	 * @param agentId agent 过滤（可选；不存在则返回空页——不抛 404，与 objective/笔记域一致）
	 * @param type    类型过滤（可选）
	 * @return 分页结果（create_time 降序，agentName 已回填）
	 */
	public IPage<LedgerVO> pageSearch(long page, long size, Long agentId, String type) {
		LambdaQueryWrapper<Ledger> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(agentId != null, Ledger::getAgentId, agentId)
				.eq(type != null && !type.isBlank(), Ledger::getType, type)
				.orderByDesc(Ledger::getCreateTime);
		IPage<Ledger> result = page(new Page<>(page, size), wrapper);
		fillAgentNames(result.getRecords());
		return result.convert(this::toVO);
	}

	/**
	 * 详情（读正文：body_inline 优先，否则读 md；md 缺失（孤儿索引行）→ 404）
	 *
	 * @param lid 台账 lid
	 * @return 详情 VO（含 body 全文）
	 */
	public LedgerDetailVO getDetail(String lid) {
		Ledger row = findByLid(lid);
		if (row == null) {
			throw new NotFoundException("台账不存在");
		}
		LedgerDetailVO vo = new LedgerDetailVO();
		fillVO(vo, row);
		if (row.getBodyPath() != null && !row.getBodyPath().isBlank()) {
			vo.setBody(ledgerStorageManager.readRaw(row.getBodyPath()));
		} else {
			vo.setBody(row.getBodyInline());
		}
		return vo;
	}

	// ============================================================
	// 门户只读（匿名）
	// ============================================================

	/**
	 * 门户：某 agent 的台账流（契约 §2.4 GET /api/portal/agents/{agentId}/ledger；agent 不存在 → 404）
	 *
	 * @param agentId agent id
	 * @return 台账列表（含 agentName，只读索引不读文件）
	 */
	public List<LedgerVO> portalListByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		Map<Long, String> nameById = agentNameMap(List.of(agentId));
		return listSorted(Wrappers.<Ledger>lambdaQuery().eq(Ledger::getAgentId, agentId))
				.stream().map(l -> {
					LedgerVO vo = toVO(l);
					vo.setAgentId(agentId);
					vo.setAgentName(nameById.get(agentId));
					return vo;
				}).collect(Collectors.toList());
	}

	/**
	 * 门户：全部 agent 台账聚合流（契约 §2.4 GET /api/portal/ledger，create_time 降序，含 agentName）
	 *
	 * @return 聚合台账流（只读索引不读文件）
	 */
	public List<LedgerVO> portalAggregate() {
		List<Ledger> all = new ArrayList<>(list(Wrappers.<Ledger>lambdaQuery()));
		all.sort(Comparator.comparing(Ledger::getCreateTime,
				Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Ledger::getId, Comparator.reverseOrder()));
		fillAgentNames(all);
		return all.stream().map(this::toVO).collect(Collectors.toList());
	}

	/**
	 * 门户：某 agent 的台账流分页（agent 不存在 → 404；只读索引不读文件）
	 * <p>
	 * 排序与 portalListByAgent 一致（create_time 降序、同时间 id 降序），
	 * SQL 层 LIMIT/OFFSET 物理分页，替代全量加载。
	 *
	 * @param agentId agent id
	 * @param page    页码（1 起）
	 * @param size    每页条数
	 * @return 分页结果（IPage，Controller setData 自动转 BPage）
	 */
	public IPage<LedgerVO> portalPageByAgent(Long agentId, long page, long size) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		LambdaQueryWrapper<Ledger> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(Ledger::getAgentId, agentId)
				.orderByDesc(Ledger::getCreateTime)
				.orderByDesc(Ledger::getId);
		IPage<Ledger> result = page(new Page<>(page, size), wrapper);
		fillAgentNames(result.getRecords());
		return result.convert(this::toVO);
	}

	/**
	 * 门户：全部 agent 台账聚合流分页（create_time 降序，含 agentName；只读索引不读文件）
	 * <p>
	 * SQL 层 LIMIT/OFFSET 物理分页，替代 portalAggregate 全表加载。
	 *
	 * @param page 页码（1 起）
	 * @param size 每页条数
	 * @return 分页结果（IPage，Controller setData 自动转 BPage）
	 */
	public IPage<LedgerVO> portalPageAggregate(long page, long size) {
		LambdaQueryWrapper<Ledger> wrapper = Wrappers.lambdaQuery();
		wrapper.orderByDesc(Ledger::getCreateTime)
				.orderByDesc(Ledger::getId);
		IPage<Ledger> result = page(new Page<>(page, size), wrapper);
		fillAgentNames(result.getRecords());
		return result.convert(this::toVO);
	}

	// ============================================================
	// 内部
	// ============================================================

	/** 列表统一按 create_time 降序、同时间 id 降序排序 */
	private List<Ledger> listSorted(LambdaQueryWrapper<Ledger> wrapper) {
		List<Ledger> all = new ArrayList<>(list(wrapper));
		all.sort(Comparator.comparing(Ledger::getCreateTime,
				Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Ledger::getId, Comparator.reverseOrder()));
		return all;
	}

	/** 批量回填 agentName（联查 t_agent，管理端分页/门户聚合用） */
	private void fillAgentNames(List<Ledger> rows) {
		if (rows == null || rows.isEmpty()) {
			return;
		}
		List<Long> ids = rows.stream().map(Ledger::getAgentId)
				.filter(java.util.Objects::nonNull).distinct().toList();
		Map<Long, String> nameById = agentNameMap(ids);
		rows.forEach(l -> l.setAgentName(
				l.getAgentId() == null ? null : nameById.get(l.getAgentId())));
	}

	private Map<Long, String> agentNameMap(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		return agentService.listByIds(ids).stream()
				.collect(Collectors.toMap(Agent::getId,
						a -> a.getDisplayName() != null ? a.getDisplayName() : a.getName(),
						(a, b) -> a));
	}

	/** 类型强校验（5 种枚举之一，契约 §1.4） */
	private String validateType(String type) {
		if (type == null || type.isBlank() || !TYPES.contains(type)) {
			throw new BadRequestException("type 必须为 5 种之一：progress/observation/decision/memo/session");
		}
		return type;
	}

	/** 索引行 → 列表 VO */
	private LedgerVO toVO(Ledger l) {
		LedgerVO vo = new LedgerVO();
		fillVO(vo, l);
		return vo;
	}

	private void fillVO(LedgerVO vo, Ledger l) {
		vo.setLid(l.getLid());
		vo.setAgentId(l.getAgentId());
		vo.setAgentName(l.getAgentName());
		vo.setType(l.getType());
		vo.setTitle(l.getTitle());
		vo.setRefTaskId(l.getRefTaskId());
		vo.setRefRequirementId(l.getRefRequirementId());
		vo.setRefObjectiveId(l.getRefObjectiveId());
		vo.setRefNoteNid(l.getRefNoteNid());
		vo.setCreateTime(l.getCreateTime());
	}
}
