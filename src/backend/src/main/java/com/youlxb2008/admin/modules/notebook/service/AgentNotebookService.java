package com.youlxb2008.admin.modules.notebook.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.service.AgentService;
import com.youlxb2008.admin.modules.notebook.entity.AgentNotebook;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.mapper.AgentNotebookMapper;
import com.youlxb2008.admin.modules.notebook.mapper.NotebookMapper;

/**
 * agent↔笔记本 N:N 关联 Service（v3.5 新增）
 * <p>
 * 唯一写入口：管理端关联维护（replaceByNotebook / replaceByAgent）、
 * 软删笔记本级联清关联（removeByNotebook）。
 * 纯关系行物理删除模型：解除关联=物理 DELETE，无 @TableLogic。
 * 幂等语义：同一 (agent, notebook) 只保留一行（uk_agent_notebook 兜底）。
 */
@Service
public class AgentNotebookService extends ServiceImpl<AgentNotebookMapper, AgentNotebook> {

	/** agent 名/档案（全量覆盖时过滤不存在的 agentId） */
	@Autowired
	private AgentService agentService;

	/** 笔记本档案（全量覆盖时过滤不存在的 notebookId；@TableLogic 自动过滤软删） */
	@Autowired
	private NotebookMapper notebookMapper;

	/**
	 * 全量覆盖结果（差集，供审计 notebook.agent.link / notebook.agent.unlink）
	 *
	 * @param added   本次新增关联的 agentId（或 notebookId）列表
	 * @param removed 本次移除关联的 agentId（或 notebookId）列表
	 */
	public record ReplaceResult(List<Long> added, List<Long> removed) {
	}

	/**
	 * 单条关联（幂等：唯一键冲突即已存在，直接忽略返回）
	 *
	 * @param agentId     agent id
	 * @param notebookId  笔记本 id
	 * @param createBy    操作人（管理员用户名 / 系统）
	 */
	public void link(Long agentId, Long notebookId, String createBy) {
		if (hasLink(agentId, notebookId)) {
			return;
		}
		AgentNotebook row = new AgentNotebook();
		row.setAgentId(agentId);
		row.setNotebookId(notebookId);
		row.setCreateBy(createBy);
		row.setCreateTime(new Date());
		save(row);
	}

	/**
	 * 单条解除（物理 DELETE）
	 *
	 * @param agentId    agent id
	 * @param notebookId 笔记本 id
	 * @return 是否实际删除了行（false=本就未关联）
	 */
	public boolean unlink(Long agentId, Long notebookId) {
		return remove(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getAgentId, agentId)
				.eq(AgentNotebook::getNotebookId, notebookId));
	}

	/**
	 * 笔记本→关联 agent 全量覆盖（事务：删旧插新，去重）
	 * <p>
	 * 管理端「关联 agent」弹窗保存语义：目标集合即最终态。
	 * 不存在的 agentId（逻辑删除/误传 id）忽略；空数组=清空全部关联。
	 *
	 * @param notebookId 笔记本 id
	 * @param agentIds   目标 agent 集合（最终态，允许为空）
	 * @param createBy   操作人（管理员用户名）
	 * @return 差集（新增/移除的 agentId），供审计 notebook.agent.link / unlink
	 */
	@Transactional(rollbackFor = Exception.class)
	public ReplaceResult replaceByNotebook(Long notebookId, List<Long> agentIds, String createBy) {
		List<Long> validIds = agentIds == null ? List.of() : agentIds.stream().distinct().toList();
		List<Long> existingIds = validIds.isEmpty() ? List.of()
				: agentService.listByIds(validIds).stream().map(Agent::getId).toList();
		Set<Long> target = new HashSet<>(existingIds);

		List<AgentNotebook> oldRows = list(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getNotebookId, notebookId));
		Set<Long> oldIds = new HashSet<>();
		List<Long> removed = new ArrayList<>();
		for (AgentNotebook row : oldRows) {
			oldIds.add(row.getAgentId());
			if (!target.contains(row.getAgentId())) {
				removed.add(row.getAgentId());
			}
		}
		List<Long> added = new ArrayList<>();
		for (Long id : target) {
			if (!oldIds.contains(id)) {
				added.add(id);
			}
		}

		if (!oldRows.isEmpty()) {
			remove(Wrappers.<AgentNotebook>lambdaQuery().eq(AgentNotebook::getNotebookId, notebookId));
		}
		Date now = new Date();
		for (Long agentId : target) {
			AgentNotebook row = new AgentNotebook();
			row.setAgentId(agentId);
			row.setNotebookId(notebookId);
			row.setCreateBy(createBy);
			row.setCreateTime(now);
			save(row);
		}
		return new ReplaceResult(added, removed);
	}

	/**
	 * agent→关联笔记本全量覆盖（事务：删旧插新，去重）
	 * <p>
	 * agent 管理页「所属笔记本」全量覆盖；不存在的 notebookId 忽略；
	 * 空数组=清空该 agent 的全部笔记本关联。
	 *
	 * @param agentId     agent id
	 * @param notebookIds 目标笔记本集合（最终态，允许为空）
	 * @param createBy    操作人（管理员用户名）
	 */
	@Transactional(rollbackFor = Exception.class)
	public void replaceByAgent(Long agentId, List<Long> notebookIds, String createBy) {
		List<Long> validIds = notebookIds == null ? List.of() : notebookIds.stream().distinct().toList();
		// @TableLogic 自动过滤已软删的笔记本
		List<Long> existingIds = validIds.isEmpty() ? List.of()
				: notebookMapper.selectBatchIds(validIds).stream().map(Notebook::getId).toList();
		Set<Long> target = new HashSet<>(existingIds);

		if (!list(Wrappers.<AgentNotebook>lambdaQuery().eq(AgentNotebook::getAgentId, agentId)).isEmpty()) {
			remove(Wrappers.<AgentNotebook>lambdaQuery().eq(AgentNotebook::getAgentId, agentId));
		}
		Date now = new Date();
		for (Long notebookId : target) {
			AgentNotebook row = new AgentNotebook();
			row.setAgentId(agentId);
			row.setNotebookId(notebookId);
			row.setCreateBy(createBy);
			row.setCreateTime(now);
			save(row);
		}
	}

	/**
	 * 笔记本下已关联的关联行列表（含 createTime/linkTime）
	 *
	 * @param notebookId 笔记本 id
	 * @return 关联行列表
	 */
	public List<AgentNotebook> listByNotebook(Long notebookId) {
		return list(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getNotebookId, notebookId)
				.orderByAsc(AgentNotebook::getCreateTime));
	}

	/**
	 * agent 关联的全部关联行列表
	 *
	 * @param agentId agent id
	 * @return 关联行列表
	 */
	public List<AgentNotebook> listByAgent(Long agentId) {
		return list(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getAgentId, agentId)
				.orderByAsc(AgentNotebook::getCreateTime));
	}

	/**
	 * agent 关联的笔记本 id 集合（D5 校验 / agent 详情回显 / 写笔记选本）
	 *
	 * @param agentId agent id
	 * @return 去重后的笔记本 id 列表；无关联返回空列表
	 */
	public List<Long> listNotebookIdsByAgent(Long agentId) {
		return list(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getAgentId, agentId)
				.select(AgentNotebook::getNotebookId))
				.stream().map(AgentNotebook::getNotebookId).distinct().toList();
	}

	/**
	 * 多 agent→笔记本 id 并集（去重）
	 *
	 * @param agentIds agent id 列表（null 不过滤；空=空结果）
	 * @return 去重后的笔记本 id 列表
	 */
	public List<Long> listNotebookIdsByAgents(List<Long> agentIds) {
		if (agentIds != null && agentIds.isEmpty()) {
			return new ArrayList<>();
		}
		return list(Wrappers.<AgentNotebook>lambdaQuery()
				.in(agentIds != null, AgentNotebook::getAgentId, agentIds)
				.select(AgentNotebook::getNotebookId))
				.stream().map(AgentNotebook::getNotebookId).distinct().toList();
	}

	/**
	 * 当前 agent 是否已关联该笔记本（D5 写权限校验）
	 *
	 * @param agentId    agent id
	 * @param notebookId 笔记本 id
	 * @return true=已关联
	 */
	public boolean hasLink(Long agentId, Long notebookId) {
		return count(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getAgentId, agentId)
				.eq(AgentNotebook::getNotebookId, notebookId)) > 0;
	}

	/**
	 * 软删笔记本级联清关联（物理 DELETE 该笔记本全部关联行，D4）
	 *
	 * @param notebookId 笔记本 id
	 * @return 删除的关联行数
	 */
	public int removeByNotebook(Long notebookId) {
		return baseMapper.delete(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getNotebookId, notebookId));
	}

	/**
	 * 删除 agent 级联清关联（物理 DELETE 该 agent 全部关联行，与 removeByNotebook 对称）
	 *
	 * @param agentId agent id
	 * @return 删除的关联行数
	 */
	public int removeByAgent(Long agentId) {
		return baseMapper.delete(Wrappers.<AgentNotebook>lambdaQuery()
				.eq(AgentNotebook::getAgentId, agentId));
	}

	/**
	 * 笔记本关联的 agent 数（管理端分页聚合）
	 *
	 * @param notebookId 笔记本 id
	 * @return 关联行数
	 */
	public long countByNotebook(Long notebookId) {
		return count(Wrappers.<AgentNotebook>lambdaQuery().eq(AgentNotebook::getNotebookId, notebookId));
	}

	/**
	 * 全部笔记本的 agent 数聚合（门户笔记本卡片 agentCount）
	 *
	 * @return notebookId → 关联 agent 数
	 */
	public Map<Long, Long> countGroupedByNotebookId() {
		Map<Long, Long> map = new HashMap<>();
		List<Map<String, Object>> rows = baseMapper.selectMaps(Wrappers.<AgentNotebook>query()
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
}