package com.youlxb2008.admin.modules.objective.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.youlxb2008.admin.modules.objective.mapper.ObjectiveMapper;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveDetailVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveStatusCountVO;
import com.youlxb2008.admin.modules.objective.vo.ObjectiveVO;

/**
 * 目标索引 Service（M4.1，agent 私域 todolist）
 * <p>
 * 纯索引 CRUD 与查询族：oid 定位 / agent 归属校验（私域）/ 管理端分页 /
 * 门户只读；详情 md 读写与子项解析委托 {@link ObjectiveStorageManager}。
 * 核心语义（契约 §1.1 + 任务书）：
 * <ul>
 *   <li>归属：agent_id 单值私域（每 agent 一个 todolist）；非本人 PATCH/DELETE → 404（防枚举）</li>
 *   <li>进度：progress = 详情 md checkbox 解析 done/total*100，写接口（PATCH 覆写描述）后刷新</li>
 *   <li>软删：is_deleted=1（md 保留）；全链路查询 is_deleted=0（fail-closed）</li>
 *   <li>关联：创建时写 t_agent_resource(type='objective', resource_id=oid)（与笔记 D6 同范式）</li>
 * </ul>
 */
@Service
public class ObjectiveService extends ServiceImpl<ObjectiveMapper, Objective> {

	/** 状态合法范围（契约：0未开始 1进行中 2已完成 3已放弃） */
	private static final int STATUS_MIN = 0;
	private static final int STATUS_MAX = 3;

	@Autowired
	private ObjectiveStorageManager objectiveStorageManager;

	@Autowired
	private AgentResourceService agentResourceService;

	@Autowired
	private AgentService agentService;

	// ============================================================
	// 定位 / 归属校验
	// ============================================================

	/**
	 * 按 oid 查索引行（对外引用键，唯一；@TableLogic 自动过滤 is_deleted=1）
	 *
	 * @param oid 对外引用键（o_ + 10 位 base62）
	 * @return 索引行；不存在/格式非法返回 null
	 */
	public Objective findByOid(String oid) {
		if (!objectiveStorageManager.isOidValid(oid)) {
			return null;
		}
		return getOne(Wrappers.<Objective>lambdaQuery().eq(Objective::getOid, oid), true);
	}

	/**
	 * 私域归属校验：目标须存在且归属当前 agent，否则统一 404（防枚举，同 M3.7 语义）
	 *
	 * @param agentId 当前 agent id
	 * @param oid     目标 oid
	 * @return 索引行（已确认归属）
	 */
	public Objective requireOwned(Long agentId, String oid) {
		Objective obj = findByOid(oid);
		if (obj == null || !agentId.equals(obj.getAgentId())) {
			throw new NotFoundException("目标不存在");
		}
		return obj;
	}

	// ============================================================
	// agent 域 / 管理端 写操作
	// ============================================================

	/**
	 * 新建目标（契约 §2.1）：生成 oid → 写详情 md（模板）→ 落索引（progress=0）→ 写作者关联行
	 *
	 * @param agentId   归属 agent id
	 * @param agentName agent 名（关联行 create_by）
	 * @param req       创建请求（title 必填）
	 * @return 新建的目标索引行（含 oid）
	 */
	@Transactional(rollbackFor = Exception.class)
	public Objective create(Long agentId, String agentName, ObjectiveCreateReq req) {
		String title = objectiveStorageManager.prepareTitle(req.getTitle());
		String oid = objectiveStorageManager.generateUniqueOid();
		// 1. 写详情 md（模板：描述 + 空子项 + 反思）→ body_path
		String bodyPath = objectiveStorageManager.writeNewBody(agentId, oid, req.getDescMd());
		// 2. 落索引（progress=0）
		Date now = new Date();
		Objective obj = new Objective();
		obj.setOid(oid);
		obj.setAgentId(agentId);
		obj.setTitle(title);
		obj.setStatus(0);
		obj.setPeriod(req.getPeriod());
		obj.setTargetDate(req.getTargetDate());
		obj.setProgress(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
		obj.setBodyPath(bodyPath);
		obj.setCreateTime(now);
		obj.setUpdateTime(now);
		save(obj);
		// 3. 作者关联（与笔记 D6 同范式，幂等）
		agentResourceService.link(agentId, AgentResource.TYPE_OBJECTIVE, oid, agentName);
		return obj;
	}

	/**
	 * 部分更新（契约 §2.1 PATCH）：findByOid + 私域归属校验 → 至少一项校验 → 更新字段 →
	 * 若改 descMd 覆写文件描述段 → 重算 progress 刷新索引。软删行（is_deleted=1）不可改（404）。
	 *
	 * @param agentId 当前 agent id
	 * @param oid     目标 oid
	 * @param req     PATCH 请求（全空 → 400）
	 * @return 更新后索引行
	 */
	@Transactional(rollbackFor = Exception.class)
	public Objective patchMeta(Long agentId, String oid, ObjectivePatchReq req) {
		Objective obj = requireOwned(agentId, oid);
		return doPatch(obj, req);
	}

	/**
	 * 管理端代改（ObjectiveAdminController PATCH）：无私域归属校验（管理员全量可见），仅目标存在（404）。
	 *
	 * @param oid 目标 oid
	 * @param req PATCH 请求（全空 → 400）
	 * @return 更新后索引行
	 */
	@Transactional(rollbackFor = Exception.class)
	public Objective patchMetaByAdmin(String oid, ObjectivePatchReq req) {
		Objective obj = findByOid(oid);
		if (obj == null) {
			throw new NotFoundException("目标不存在");
		}
		return doPatch(obj, req);
	}

	/**
	 * PATCH 核心（findByOid 已确认存在；无归属维度）：至少一项校验 → 更新字段 →
	 * 若改 descMd 覆写文件描述段 → 重算 progress 刷新索引
	 */
	private Objective doPatch(Objective obj, ObjectivePatchReq req) {
		if (req == null
				|| (req.getTitle() == null && req.getPeriod() == null && req.getTargetDate() == null
						&& req.getStatus() == null && req.getDescMd() == null)) {
			throw new BadRequestException("至少修改一项（title/period/targetDate/status/descMd）");
		}
		Objective update = new Objective();
		update.setId(obj.getId());
		if (req.getTitle() != null) {
			update.setTitle(objectiveStorageManager.prepareTitle(req.getTitle()));
		}
		if (req.getPeriod() != null) {
			update.setPeriod(req.getPeriod());
		}
		if (req.getTargetDate() != null) {
			update.setTargetDate(req.getTargetDate());
		}
		if (req.getStatus() != null) {
			validateStatus(req.getStatus());
			update.setStatus(req.getStatus());
		}
		// 改 descMd → 覆写文件描述段（子项/反思保留），随后从新文件重算 progress 刷新索引
		if (req.getDescMd() != null) {
			String newBody = objectiveStorageManager.replaceDesc(obj.getBodyPath(), req.getDescMd());
			update.setProgress(objectiveStorageManager.calcProgress(newBody));
		}
		update.setUpdateTime(new Date());
		updateById(update);

		// 回填内存实体供返回（避免再查库）
		if (update.getTitle() != null) {
			obj.setTitle(update.getTitle());
		}
		if (update.getPeriod() != null) {
			obj.setPeriod(update.getPeriod());
		}
		if (update.getTargetDate() != null) {
			obj.setTargetDate(update.getTargetDate());
		}
		if (update.getStatus() != null) {
			obj.setStatus(update.getStatus());
		}
		if (update.getProgress() != null) {
			obj.setProgress(update.getProgress());
		}
		obj.setUpdateTime(update.getUpdateTime());
		return obj;
	}

	/**
	 * 软删（契约 §2.1 DELETE）：findByOid + 私域归属校验 → is_deleted=1，md 文件保留。
	 * 关联行保留（软删行不可见，无读侧副作用）。幂等：已软删/不存在统一 404（防枚举）。
	 *
	 * @param agentId 当前 agent id
	 * @param oid     目标 oid
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long agentId, String oid) {
		requireOwned(agentId, oid);
		doDelete(oid);
	}

	/**
	 * 管理端软删（ObjectiveAdminController DELETE）：无私域归属校验，仅目标存在（404）。
	 *
	 * @param oid 目标 oid
	 */
	@Transactional(rollbackFor = Exception.class)
	public void deleteByAdmin(String oid) {
		if (findByOid(oid) == null) {
			throw new NotFoundException("目标不存在");
		}
		doDelete(oid);
	}

	/** 软删核心：is_deleted=1（md 保留）；更新失败（并发删除）抛 404 */
	private void doDelete(String oid) {
		boolean ok = lambdaUpdate().eq(Objective::getOid, oid)
				.set(Objective::getIsDeleted, 1)
				.set(Objective::getUpdateTime, new Date())
				.update();
		if (!ok) {
			throw new NotFoundException("目标不存在");
		}
	}

	// ============================================================
	// 查询族
	// ============================================================

	/**
	 * agent 域：自己（当前 agent）的目标列表（契约 §2.1，update_time 降序）
	 *
	 * @param agentId 当前 agent id
	 * @return 目标列表（只读索引，不读文件）
	 */
	public List<ObjectiveVO> listOwn(Long agentId) {
		return listSorted(Wrappers.<Objective>lambdaQuery().eq(Objective::getAgentId, agentId))
				.stream().map(this::toVO).collect(Collectors.toList());
	}

	/**
	 * 某 agent 的全部目标（管理端 GET /agents/{agentId}/objectives；agent 不存在 → 404）
	 *
	 * @param agentId agent id
	 * @return 目标列表（含 agentName 回填）
	 */
	public List<ObjectiveVO> listByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		Map<Long, String> nameById = agentNameMap(List.of(agentId));
		return listSorted(Wrappers.<Objective>lambdaQuery().eq(Objective::getAgentId, agentId))
				.stream().map(o -> {
					ObjectiveVO vo = toVO(o);
					vo.setAgentId(agentId);
					vo.setAgentName(nameById.get(agentId));
					return vo;
				}).collect(Collectors.toList());
	}

	/**
	 * 管理端分页（契约 §2.1 GET /objectives）：agentId 过滤 + keyword 搜 title LIKE
	 *
	 * @param page    页码（从 1 开始）
	 * @param size    每页条数
	 * @param agentId agent 过滤（可选；不存在则返回空页——不抛 404，与笔记域一致）
	 * @param keyword 标题 LIKE 搜索（可选）
	 * @return 分页结果（update_time 降序，agentName 已回填）
	 */
	public IPage<ObjectiveVO> pageSearch(long page, long size, Long agentId, String keyword) {
		LambdaQueryWrapper<Objective> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(agentId != null, Objective::getAgentId, agentId)
				.like(keyword != null && !keyword.isBlank(), Objective::getTitle, keyword)
				.orderByDesc(Objective::getUpdateTime);
		IPage<Objective> result = page(new Page<>(page, size), wrapper);
		fillAgentNames(result.getRecords());
		return result.convert(this::toVO);
	}

	/**
	 * 详情（读 md 全文 + checkbox 子项解析）
	 * <p>
	 * agent 域/门户共用；md 文件缺失（孤儿索引行）→ 404。
	 *
	 * @param oid 目标 oid
	 * @return 详情 VO
	 */
	public ObjectiveDetailVO getDetail(String oid) {
		Objective obj = findByOid(oid);
		if (obj == null) {
			throw new NotFoundException("目标不存在");
		}
		String contentMd = objectiveStorageManager.readRaw(obj.getBodyPath());
		ObjectiveDetailVO vo = new ObjectiveDetailVO();
		vo.setOid(obj.getOid());
		vo.setAgentId(obj.getAgentId());
		vo.setTitle(obj.getTitle());
		vo.setStatus(obj.getStatus());
		vo.setPeriod(obj.getPeriod());
		vo.setTargetDate(obj.getTargetDate());
		vo.setProgress(obj.getProgress());
		vo.setContentMd(contentMd);
		vo.setSubItems(objectiveStorageManager.parseSubItems(contentMd));
		vo.setUpdateTime(obj.getUpdateTime());
		return vo;
	}

	// ============================================================
	// 门户只读（匿名）
	// ============================================================

	/**
	 * 门户：某 agent 的目标 todolist（契约 §3，agent 不存在 → 404；只读索引不读文件）
	 *
	 * @param agentId agent id
	 * @return 目标列表
	 */
	public List<ObjectiveVO> portalListByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		return listSorted(Wrappers.<Objective>lambdaQuery().eq(Objective::getAgentId, agentId))
				.stream().map(this::toVO).collect(Collectors.toList());
	}

	/**
	 * 门户：某 agent 的目标 todolist 分页（agent 不存在 → 404；只读索引不读文件）
	 * <p>
	 * 排序与 portalListByAgent 一致（update_time 降序、同时间 id 降序），
	 * SQL 层 LIMIT/OFFSET 物理分页，替代全量加载。
	 *
	 * @param agentId agent id
	 * @param status  状态筛选（可选；0未开始/1进行中/2已完成/3已放弃，null=全部）
	 * @param page    页码（1 起）
	 * @param size    每页条数
	 * @return 分页结果（IPage，Controller setData 自动转 BPage）
	 */
	public IPage<ObjectiveVO> portalPageByAgent(Long agentId, Integer status, long page, long size) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		LambdaQueryWrapper<Objective> wrapper = Wrappers.lambdaQuery();
		wrapper.eq(Objective::getAgentId, agentId)
				.eq(status != null, Objective::getStatus, status)
				.orderByDesc(Objective::getUpdateTime)
				.orderByDesc(Objective::getId);
		IPage<Objective> result = page(new Page<>(page, size), wrapper);
		return result.convert(this::toVO);
	}

	/**
	 * 门户：某 agent 目标状态计数（全量口径，不受 status 筛选影响）。
	 * <p>
	 * 供详情页筛选胶囊与 Tab 角标用：前端旧实现基于「当前筛选下已加载条目」统计，
	 * 切筛选后计数漂移（胶囊数字随筛选变、与全部数对不上）；改由服务端一次返回
	 * 全量各状态数，前端进页/切 agent 拉一次，切筛选不重拉。
	 * 仅统计未软删（@TableLogic 自动追加 is_deleted=0）；agent 不存在 → 404。
	 *
	 * @param agentId agent id
	 * @return 各状态计数（total = 四态之和）
	 */
	public ObjectiveStatusCountVO portalStatusCountsByAgent(Long agentId) {
		if (agentService.getById(agentId) == null) {
			throw new NotFoundException("agent 不存在");
		}
		QueryWrapper<Objective> wrapper = new QueryWrapper<>();
		wrapper.select("status", "COUNT(*) AS cnt")
				.eq("agent_id", agentId)
				.groupBy("status");
		Map<Integer, Long> byStatus = new HashMap<>();
		for (Map<String, Object> row : listMaps(wrapper)) {
			Object status = row.get("status");
			Object cnt = row.get("cnt");
			if (status instanceof Number s && cnt instanceof Number c) {
				byStatus.put(s.intValue(), c.longValue());
			}
		}
		ObjectiveStatusCountVO vo = new ObjectiveStatusCountVO();
		vo.setPending(byStatus.getOrDefault(0, 0L));
		vo.setDoing(byStatus.getOrDefault(1, 0L));
		vo.setDone(byStatus.getOrDefault(2, 0L));
		vo.setDropped(byStatus.getOrDefault(3, 0L));
		vo.setTotal(vo.getPending() + vo.getDoing() + vo.getDone() + vo.getDropped());
		return vo;
	}

	/**
	 * 门户：目标详情（契约 §3；目标不存在/已软删 → 404；读 md + 子项）
	 *
	 * @param oid 目标 oid
	 * @return 详情 VO
	 */
	public ObjectiveDetailVO portalDetail(String oid) {
		return getDetail(oid);
	}

	// ============================================================
	// 内部
	// ============================================================

	/** 列表统一按 update_time 降序、同时间 id 降序排序 */
	private List<Objective> listSorted(LambdaQueryWrapper<Objective> wrapper) {
		List<Objective> all = new ArrayList<>(list(wrapper));
		all.sort(Comparator.comparing(Objective::getUpdateTime,
				Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Objective::getId, Comparator.reverseOrder()));
		return all;
	}

	/** 批量回填 agentName（联查 t_agent，管理端分页用） */
	private void fillAgentNames(List<Objective> objectives) {
		if (objectives == null || objectives.isEmpty()) {
			return;
		}
		List<Long> ids = objectives.stream().map(Objective::getAgentId)
				.filter(java.util.Objects::nonNull).distinct().toList();
		Map<Long, String> nameById = agentNameMap(ids);
		objectives.forEach(o -> o.setAgentName(
				o.getAgentId() == null ? null : nameById.get(o.getAgentId())));
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

	private void validateStatus(Integer status) {
		if (status == null || status < STATUS_MIN || status > STATUS_MAX) {
			throw new BadRequestException("status 取值 0-3（0未开始 1进行中 2已完成 3已放弃）");
		}
	}

	/** 索引行 → 列表 VO（不含 agentName，调用方按需回填） */
	private ObjectiveVO toVO(Objective o) {
		ObjectiveVO vo = new ObjectiveVO();
		vo.setOid(o.getOid());
		vo.setAgentId(o.getAgentId());
		vo.setAgentName(o.getAgentName());
		vo.setTitle(o.getTitle());
		vo.setStatus(o.getStatus());
		vo.setPeriod(o.getPeriod());
		vo.setTargetDate(o.getTargetDate());
		vo.setProgress(o.getProgress());
		vo.setUpdateTime(o.getUpdateTime());
		return vo;
	}
}
