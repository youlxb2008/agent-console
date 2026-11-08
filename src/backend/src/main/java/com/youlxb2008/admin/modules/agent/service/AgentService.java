package com.youlxb2008.admin.modules.agent.service;

import java.util.Date;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;

/**
 * agent 档案 Service
 * <p>
 * 基础 CRUD 由 {@link ServiceImpl} 提供（逻辑删除由 @TableLogic 自动处理）。
 * 跨实体编排（如建 agent 时级联生成 key）由上层 Manager 负责，本类保持单实体职责。
 */
@Service
public class AgentService extends ServiceImpl<AgentMapper, Agent> {

	/**
	 * 按 agent 名查询（name 唯一）
	 *
	 * @param name agent 名
	 * @return agent；不存在返回 null
	 */
	public Agent getByName(String name) {
		return getOne(Wrappers.<Agent>lambdaQuery().eq(Agent::getName, name), true);
	}

	/**
	 * 查询启用中的 agent（status=1 且未逻辑删除）
	 *
	 * @param id agent id
	 * @return agent；不存在或已 suspend 返回 null
	 */
	public Agent getActiveById(Long id) {
		return getOne(Wrappers.<Agent>lambdaQuery().eq(Agent::getId, id).eq(Agent::getStatus, 1), true);
	}

	/**
	 * 管理端：agent 分页列表（契约 api.md §2，GET /agents）
	 * <p>
	 * 支持按 name/displayName 模糊搜索；key 数、最近使用由 Controller 聚合填充。
	 *
	 * @param page    页码（从 1 开始）
	 * @param size    每页条数
	 * @param keyword 关键字（可选，匹配 name/displayName）
	 * @return 分页结果
	 */
	public IPage<Agent> pageAgents(long page, long size, String keyword) {
		var wrapper = Wrappers.<Agent>lambdaQuery()
				.and(keyword != null && !keyword.isBlank(), w -> w.like(Agent::getName, keyword)
						.or().like(Agent::getDisplayName, keyword))
				.orderByDesc(Agent::getCreateTime);
		return page(new Page<>(page, size), wrapper);
	}

	/**
	 * 管理端：创建 agent（契约 api.md §2，POST /agents）
	 * <p>
	 * name 唯一；status 缺省 0=suspended 待审批（或显式传 1 直接启用）。
	 *
	 * @param name        agent 名（唯一）
	 * @param displayName 展示名
	 * @param avatar      头像 URL（可选，null=无头像）
	 * @param description 能力描述
	 * @param status      1=active 0=suspended，null 时默认 0
	 * @param createdBy   注册人（t_user.id）
	 * @return 新建的 agent
	 */
	public Agent createAgent(String name, String displayName, String avatar, String description, Integer status,
			Long createdBy) {
		if (getByName(name) != null) {
			throw new BusinessException("agent 名已存在：" + name);
		}
		Agent agent = new Agent();
		agent.setName(name);
		agent.setDisplayName(displayName);
		// 头像 URL 原样落库（前端保证来自上传接口返回值；空串归一为 null 保持库干净）
		agent.setAvatar(avatar != null && avatar.isBlank() ? null : avatar);
		agent.setDescription(description);
		agent.setStatus(status == null ? 0 : status);
		agent.setCreatedBy(createdBy);
		agent.setCreateTime(new Date());
		agent.setUpdateTime(new Date());
		save(agent);
		return agent;
	}

	/**
	 * 管理端：更新 agent（契约 api.md §2，PUT /agents/{id}）
	 * <p>
	 * 只允许改 display_name/avatar/description/status（name 为唯一键不可改）；
	 * 非 null 字段才更新。avatar 语义：null=不修改，空串=清除头像（回退色块兜底）。
	 * suspend 即刻生效（key 缓存 30s 内过期，第 1 包已实现）。
	 *
	 * @param id          agent id
	 * @param displayName 展示名（可选）
	 * @param avatar      头像 URL（可选，空串=清除）
	 * @param description 能力描述（可选）
	 * @param status      状态 1/0（可选）
	 * @return 更新后的 agent
	 */
	public Agent updateAgent(Long id, String displayName, String avatar, String description, Integer status) {
		Agent agent = getById(id);
		if (agent == null) {
			throw new BusinessException("agent 不存在");
		}
		if (displayName != null && !displayName.isBlank()) {
			agent.setDisplayName(displayName);
		}
		// 头像：非 null 才更新；空串归一为 null（清除头像，门户回退色块兜底）
		if (avatar != null) {
			agent.setAvatar(avatar.isBlank() ? null : avatar);
		}
		if (description != null) {
			agent.setDescription(description);
		}
		if (status != null) {
			agent.setStatus(status);
		}
		agent.setUpdateTime(new Date());
		updateById(agent);
		return agent;
	}
}
