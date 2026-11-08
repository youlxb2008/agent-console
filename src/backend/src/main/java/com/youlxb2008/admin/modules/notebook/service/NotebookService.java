package com.youlxb2008.admin.modules.notebook.service;

import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.note.entity.NoteMeta;
import com.youlxb2008.admin.modules.note.mapper.NoteMetaMapper;
import com.youlxb2008.admin.modules.notebook.entity.AgentNotebook;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.mapper.AgentNotebookMapper;
import com.youlxb2008.admin.modules.notebook.mapper.NotebookMapper;
import com.youlxb2008.admin.modules.notebook.vo.NotebookAgentVO;
import com.youlxb2008.admin.modules.notebook.vo.NotebookVO;

/**
 * 笔记本 Service（v3.5 新增）
 * <p>
 * 独立笔记本实体核心写路径 + 分页聚合。
 * <ul>
 * <li>create/update：name 在未软删范围唯一（冲突抛 {@link BusinessException}）</li>
 * <li>delete（D4 软删）：is_deleted=1 + name 改写 {@code {name}__deleted_{id}} 释放原名
 * + 物理删 t_agent_notebook 全部关联行 + 不动 t_note_meta（数据保留）</li>
 * <li>page：noteCount（t_note_meta.groupBy notebook_id）与
 * agentCount（t_agent_notebook.groupBy）两次聚合填充</li>
 * </ul>
 * <b>依赖方向（避免循环）</b>：本类仅依赖 note/notebook 的 Mapper 层（聚合）与
 * {@link AgentNotebookService}（级联清关联），不反向依赖 note 的 Service。
 */
@Service
public class NotebookService extends ServiceImpl<NotebookMapper, Notebook> {

	/** 笔记索引 Mapper（noteCount/lastNoteAt 聚合，避免 service 循环依赖） */
	@Autowired
	private NoteMetaMapper noteMetaMapper;

	/** agent↔笔记本关联 Mapper（agentCount 聚合） */
	@Autowired
	private AgentNotebookMapper agentNotebookMapper;

	/** 关联维护（软删级联清关联） */
	@Autowired
	private AgentNotebookService agentNotebookService;

	/**
	 * 新建笔记本（name 在未软删范围唯一校验）
	 *
	 * @param name        笔记本名（唯一）
	 * @param description 简介（可选）
	 * @param cover       封面 URL（可选）
	 * @param status      1=active 0=hidden，null 默认 1
	 * @param createdBy   创建人（t_user.id）
	 * @return 新建的笔记本
	 */
	public Notebook create(String name, String description, String cover, String logo, String hero, Integer status, Long createdBy) {
		if (countByName(name) > 0) {
			throw new BusinessException("笔记本名已存在：" + name);
		}
		Notebook nb = new Notebook();
		nb.setName(name);
		nb.setDescription(description);
		nb.setCover(cover);
		nb.setLogo(logo);
		nb.setHero(hero);
		nb.setStatus(status == null ? 1 : status);
		nb.setCreatedBy(createdBy);
		Date now = new Date();
		nb.setCreateTime(now);
		nb.setUpdateTime(now);
		save(nb);
		return nb;
	}

	/**
	 * 编辑笔记本（name 唯一校验，排除自身；非 null 字段才更新）
	 *
	 * @param id          笔记本 id
	 * @param name        笔记本名（可选，改了才校验唯一）
	 * @param description 简介（可选）
	 * @param cover       封面 URL（可选）
	 * @param status      状态 1/0（可选）
	 * @return 更新后的笔记本
	 */
	public Notebook update(Long id, String name, String description, String cover, String logo, String hero, Integer status) {
		Notebook nb = getById(id);
		if (nb == null) {
			throw new NotFoundException("笔记本不存在");
		}
		if (name != null && !name.isBlank() && !name.equals(nb.getName())) {
			if (countByNameExclude(name, id) > 0) {
				throw new BusinessException("笔记本名已存在：" + name);
			}
			nb.setName(name);
		}
		if (description != null) {
			nb.setDescription(description);
		}
		if (cover != null) {
			nb.setCover(cover);
		}
		if (logo != null) {
			nb.setLogo(logo);
		}
		if (hero != null) {
			nb.setHero(hero);
		}
		if (status != null) {
			nb.setStatus(status);
		}
		nb.setUpdateTime(new Date());
		updateById(nb);
		return nb;
	}

	/**
	 * 软删笔记本（D4：is_deleted=1 + name 释放原名 + 物理删 t_agent_notebook 关联 + 保留 t_note_meta）
	 *
	 * @param id 笔记本 id
	 */
	public void delete(Long id) {
		Notebook nb = getById(id);
		if (nb == null) {
			throw new NotFoundException("笔记本不存在");
		}
		// 物理删除该笔记本在 t_agent_notebook 的全部关联行（agent 关联自动解除）
		agentNotebookService.removeByNotebook(id);
		// 软删标记 + name 改写释放原名（幂等：重删/恢复时同步还原）
		// 使用 UpdateWrapper 显式 SET is_deleted（@TableLogic 与 updateById 混用时
		// is_deleted 可能不会被写入 SET 子句，故用 UpdateWrapper 保证幂等）
		update(Wrappers.<Notebook>lambdaUpdate()
				.set(Notebook::getIsDeleted, 1)
				.set(Notebook::getName, nb.getName() + "__deleted_" + id)
				.set(Notebook::getUpdateTime, new Date())
				.eq(Notebook::getId, id));
	}

	/**
	 * 管理端：笔记本分页（过滤 is_deleted=0，含 noteCount/agentCount 聚合）
	 *
	 * @param page    页码（从 1 开始）
	 * @param size    每页条数
	 * @param keyword 名称 LIKE 搜索（可选）
	 * @return 分页结果（create_time 降序）
	 */
	public IPage<NotebookVO> pageVO(long page, long size, String keyword) {
		IPage<Notebook> result = page(new Page<>(page, size), Wrappers.<Notebook>lambdaQuery()
				.like(keyword != null && !keyword.isBlank(), Notebook::getName, keyword)
				.orderByDesc(Notebook::getCreateTime));
		Map<Long, NoteAgg> noteAgg = noteAggGrouped();
		Map<Long, Long> agentCnt = countAgentGrouped();
		return result.convert(nb -> toVO(nb, noteAgg, agentCnt));
	}

	/**
	 * 门户：未软删且启用笔记本概览（契约 api.md §3，GET /api/portal/notebooks）
	 * <p>
	 * 未软删 + status=1（active）笔记本 + noteCount/lastNoteAt/agentCount 聚合
	 * （D3 全部门户公开；status=0 hidden 为管理端保留，不出现在门户——
	 * 前端设计「隐藏后笔记本不再出现在门户」）。
	 *
	 * @return 笔记本 VO 列表（create_time 降序，仅 active）
	 */
	public List<NotebookVO> portalNotebooks() {
		Map<Long, NoteAgg> noteAgg = noteAggGrouped();
		Map<Long, Long> agentCnt = agentNotebookService.countGroupedByNotebookId();
		return listAll().stream()
				.filter(nb -> Integer.valueOf(1).equals(nb.getStatus()))
				.map(nb -> toVO(nb, noteAgg, agentCnt)).toList();
	}

	/**
	 * 门户：某 agent 名下已关联笔记本概览（GET /api/portal/agents/{agentId}/notebooks）
	 * <p>
	 * 关联表 t_agent_notebook 取该 agent 的 notebookId 集 → 仅保留未软删
	 * （listByIds 经 @TableLogic 自动过滤）且 status=1（active）的笔记本 +
	 * noteCount/lastNoteAt/agentCount 聚合；排序与 portalNotebooks() 一致
	 * （create_time 降序）。agent 无任何关联时返回空列表（agent 是否存在的
	 * 404 校验由 Controller 负责，与 objectives/ledger 端点语义对齐）。
	 *
	 * @param agentId agent id
	 * @return 笔记本 VO 列表（create_time 降序，仅 active）
	 */
	public List<NotebookVO> portalNotebooksByAgent(Long agentId) {
		List<Long> ids = agentNotebookService.listNotebookIdsByAgent(agentId);
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<Long, NoteAgg> noteAgg = noteAggGrouped();
		Map<Long, Long> agentCnt = agentNotebookService.countGroupedByNotebookId();
		return listByIds(ids).stream()
				.filter(nb -> Integer.valueOf(1).equals(nb.getStatus()))
				.sorted(Comparator.comparing(Notebook::getCreateTime).reversed())
				.map(nb -> toVO(nb, noteAgg, agentCnt))
				.toList();
	}

	/**
	 * 未软删全量（管理端下拉 / agent 写笔记选本）
	 *
	 * @return 未软删笔记本列表（create_time 降序）
	 */
	public List<Notebook> listAll() {
		return list(Wrappers.<Notebook>lambdaQuery().orderByDesc(Notebook::getCreateTime));
	}

	/**
	 * 查询笔记本下笔记数（t_note_meta 聚合，供 agent 详情回显）
	 *
	 * @param notebookId 笔记本 id
	 * @return 笔记数
	 */
	public long countNotes(Long notebookId) {
		return noteMetaMapper.selectCount(Wrappers.<NoteMeta>lambdaQuery()
				.eq(NoteMeta::getNotebookId, notebookId));
	}

	/**
	 * 依 id 取未软删笔记本（null=不存在或已软删）
	 *
	 * @param id 笔记本 id
	 * @return 笔记本；不存在返回 null
	 */
	public Notebook getByIdOrNull(Long id) {
		return getById(id);
	}

	/**
	 * 笔记本下已关联 agent 列表（委托 Mapper JOIN 查询，保持 Controller→Service→Mapper 分层）
	 *
	 * @param notebookId 笔记本 id
	 * @return 已关联 agent 列表（含关联时间，按关联时间正序）
	 */
	public List<NotebookAgentVO> listAgentsByNotebook(Long notebookId) {
		return baseMapper.selectAgentsByNotebookId(notebookId);
	}

	// ------------------------------------------------------------
	// 内部：唯一性校验 / 聚合组装
	// ------------------------------------------------------------

	/** 未软删范围内同名校验（@TableLogic 自动过滤 is_deleted） */
	private long countByName(String name) {
		return count(Wrappers.<Notebook>lambdaQuery().eq(Notebook::getName, name));
	}

	/** 编辑唯一性：排除自身 */
	private long countByNameExclude(String name, Long excludeId) {
		return count(Wrappers.<Notebook>lambdaQuery().eq(Notebook::getName, name).ne(Notebook::getId, excludeId));
	}

	/** 笔记聚合：notebook_id → COUNT + MAX(update_time)（t_note_meta 单次分组查询） */
	private Map<Long, NoteAgg> noteAggGrouped() {
		Map<Long, NoteAgg> map = new HashMap<>();
		List<Map<String, Object>> rows = noteMetaMapper.selectMaps(Wrappers.<NoteMeta>query()
				.select("notebook_id AS nb", "COUNT(*) AS cnt", "MAX(update_time) AS last")
				.isNotNull("notebook_id").groupBy("notebook_id"));
		for (Map<String, Object> r : rows) {
			Object nb = r.get("nb");
			if (nb == null) {
				continue;
			}
			Object cnt = r.get("cnt");
			Object last = r.get("last");
			map.put(((Number) nb).longValue(),
					new NoteAgg(cnt == null ? 0L : ((Number) cnt).longValue(),
							toDate(last)));
		}
		return map;
	}

	/**
	 * JDBC 聚合列（MAX(update_time)）转 {@link Date}：mysql-connector-j 8.x 对
	 * DATETIME 列经 selectMaps 可能返回 {@link java.time.LocalDateTime}，直接强转
	 * {@link java.util.Date} 抛 ClassCastException；这里统一兼容处理。
	 */
	private static Date toDate(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Date d) {
			return d; // 含 java.sql.Date / java.sql.Timestamp
		}
		if (v instanceof java.time.LocalDateTime ldt) {
			return Date.from(ldt.atZone(java.time.ZoneId.systemDefault()).toInstant());
		}
		if (v instanceof java.time.LocalDate ld) {
			return Date.from(ld.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
		}
		if (v instanceof java.time.LocalTime lt) {
			return Date.from(lt.atDate(java.time.LocalDate.now()).atZone(java.time.ZoneId.systemDefault()).toInstant());
		}
		return null;
	}

	/** agent 数聚合：notebook_id → COUNT(t_agent_notebook) */
	private Map<Long, Long> countAgentGrouped() {
		Map<Long, Long> map = new HashMap<>();
		List<Map<String, Object>> rows = agentNotebookMapper.selectMaps(Wrappers.<AgentNotebook>query()
				.select("notebook_id AS nb", "COUNT(*) AS cnt")
				.isNotNull("notebook_id").groupBy("notebook_id"));
		for (Map<String, Object> r : rows) {
			Object nb = r.get("nb");
			Object cnt = r.get("cnt");
			if (nb != null) {
				map.put(((Number) nb).longValue(), cnt == null ? 0L : ((Number) cnt).longValue());
			}
		}
		return map;
	}

	/** Notebook → NotebookVO（填充聚合） */
	private NotebookVO toVO(Notebook nb, Map<Long, NoteAgg> noteAgg, Map<Long, Long> agentCnt) {
		NotebookVO vo = new NotebookVO();
		vo.setId(nb.getId());
		vo.setName(nb.getName());
		vo.setDescription(nb.getDescription());
		vo.setCover(nb.getCover());
		vo.setLogo(nb.getLogo());
		vo.setHero(nb.getHero());
		vo.setStatus(nb.getStatus());
		NoteAgg agg = noteAgg.get(nb.getId());
		vo.setNoteCount(agg == null ? 0L : agg.count);
		vo.setLastNoteAt(agg == null ? null : agg.lastNoteAt);
		vo.setAgentCount(agentCnt.getOrDefault(nb.getId(), 0L));
		vo.setCreateTime(nb.getCreateTime());
		vo.setUpdateTime(nb.getUpdateTime());
		return vo;
	}

	/** t_note_meta 分组聚合（notebook_id → 笔记数 + 最近更新） */
	private record NoteAgg(long count, Date lastNoteAt) {
	}
}
