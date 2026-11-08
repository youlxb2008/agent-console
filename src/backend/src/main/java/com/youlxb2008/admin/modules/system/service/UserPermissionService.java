package com.youlxb2008.admin.modules.system.service;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.entity.UserRolePermission;
import com.youlxb2008.admin.modules.system.mapper.UserPermissionMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserPermissionService extends ServiceImpl<UserPermissionMapper, UserPermission> {

	/** 角色-权限关联服务，用于根据角色ID查询权限ID */
	@Autowired
	private UserRolePermissionService rolePermissionService;

	/**
	 * 获取完整的权限菜单树（通常用于管理员配置或前端侧边栏） 按 sortOrder 升序排序，仅返回启用状态（status = 1）的节点
	 */
	public List<UserPermission> getPermissionTree() {
		// 查询所有启用的权限（status=1 或 status 为 null 兼容旧数据），按排序值升序
		List<UserPermission> allPermissions = this.list(
				new LambdaQueryWrapper<UserPermission>()
						.and(wrapper -> wrapper.eq(UserPermission::getStatus, 1).or().isNull(UserPermission::getStatus))
						.orderByAsc(UserPermission::getSortOrder));

		// 根节点的 parent_id = 0 或 null
		return buildTree(allPermissions, 0);
	}

	/**
	 * 将扁平的权限列表构建为树形结构
	 * <p>
	 * 公开方法，供 Controller 组装"当前用户可见菜单树"等场景复用。
	 * 约定：parentId 为 0 或 null 视为根节点。
	 * </p>
	 *
	 * @param nodes   扁平权限列表（通常是已筛选过的子集）
	 * @param parentId 起始父 ID（根传 0）
	 * @return 树形结构列表
	 */
	public List<UserPermission> buildTree(List<UserPermission> nodes, Integer parentId) {
		return nodes.stream()
				.filter(node -> Objects.equals(node.getParentId(), parentId)
						|| (parentId != null && parentId == 0 && node.getParentId() == null))
				.peek(node -> node.setChildren(buildTree(nodes, node.getId())))
				.collect(Collectors.toList());
	}

	/**
	 * 根据角色ID列表获取所有权限标识（permissionKey 集合），用于自定义鉴权
	 * 通过 t_role_permission 关联表查询角色对应的权限ID，再查询权限详情
	 * 
	 * @param roleIds 角色ID集合
	 * @return 权限标识集合
	 */
	public Set<String> getPermissionKeysByRoleIds(Collection<Integer> roleIds) {
		if (roleIds == null || roleIds.isEmpty()) {
			return Collections.emptySet();
		}
		// 通过角色-权限关联表查询权限ID列表
		List<UserRolePermission> rolePermissions = rolePermissionService.list(
				new LambdaQueryWrapper<UserRolePermission>()
						.in(UserRolePermission::getRoleId, roleIds));
		if (rolePermissions.isEmpty()) {
			return Collections.emptySet();
		}
		// 提取权限ID，查询对应的权限实体
		List<Integer> permissionIds = rolePermissions.stream()
				.map(UserRolePermission::getPermissionId)
				.distinct()
				.collect(Collectors.toList());
		List<UserPermission> permissions = this.list(new LambdaQueryWrapper<UserPermission>()
				.in(UserPermission::getId, permissionIds)
				.eq(UserPermission::getStatus, 1));
		return permissions.stream()
				.map(UserPermission::getPermissionKey)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

	/**
	 * 新增权限项（自动计算 level）
	 */
	@DSTransactional
	public boolean addPermission(UserPermission permission) {
		// 设置默认值
		if (permission.getSortOrder() == null) {
			permission.setSortOrder(0);
		}
		if (permission.getStatus() == null) {
			permission.setStatus(1); // 默认启用
		}

		// 计算 level
		if (permission.getParentId() == null || permission.getParentId() == 0) {
			permission.setLevel(1);
			permission.setParentId(0); // 约定根节点 parent_id = 0
		} else {
			UserPermission parent = this.getById(permission.getParentId());
			if (parent == null) {
				throw new IllegalArgumentException("父级权限不存在");
			}
			permission.setLevel(parent.getLevel() + 1);
		}

		return this.save(permission);
	}

	/**
	 * 更新权限项（不允许修改层级结构，如 parent_id 变更需谨慎） 此处简化：允许改 parent，但需重新计算子树 level（复杂，一般不建议）
	 * 更安全做法：禁止修改 parentId，或提供专用“移动节点”接口
	 */
	@DSTransactional
	public boolean updatePermission(UserPermission permission) {
		UserPermission existing = this.getById(permission.getId());
		if (existing == null) {
			throw new IllegalArgumentException("权限不存在");
		}

		// 如果 parent_id 被修改，需校验新父节点是否存在
		if (!Objects.equals(existing.getParentId(), permission.getParentId())) {
			if (permission.getParentId() != null && permission.getParentId() != 0) {
				UserPermission newParent = this.getById(permission.getParentId());
				if (newParent == null) {
					throw new IllegalArgumentException("新的父级权限不存在");
				}
				permission.setLevel(newParent.getLevel() + 1);
			} else {
				permission.setLevel(1);
				permission.setParentId(0);
			}
			// ⚠️ 注意：此操作不会自动更新子节点的 level！
			// 如需完整支持移动，需递归更新整棵子树（性能敏感，慎用）
		}

		return this.updateById(permission);
	}

	/**
	 * 删除权限（级联删除子节点 + 清理角色-权限关联）
	 * <p>
	 * 递归收集当前节点及所有子孙节点 ID，统一删除权限记录和 role_permission 关联。
	 * </p>
	 *
	 * @param id 待删除的权限节点 ID
	 * @return 是否删除成功
	 */
	@DSTransactional
	public boolean removePermission(Integer id) {
		// 递归收集当前节点及所有子孙节点 ID
		List<Integer> allIds = collectDescendantIds(id);

		// 清理 t_role_permission 中这些权限的关联记录
		rolePermissionService.remove(
				new LambdaQueryWrapper<UserRolePermission>()
						.in(UserRolePermission::getPermissionId, allIds));

		// 批量删除权限节点
		return this.removeByIds(allIds);
	}

	/**
	 * 递归收集指定节点及其所有子孙节点的 ID
	 *
	 * @param parentId 起始节点 ID
	 * @return 包含自身及所有子孙节点的 ID 列表
	 */
	private List<Integer> collectDescendantIds(Integer parentId) {
		List<Integer> ids = new java.util.ArrayList<>();
		ids.add(parentId);
		// 查询直接子节点
		List<UserPermission> children = this.list(
				new LambdaQueryWrapper<UserPermission>().eq(UserPermission::getParentId, parentId));
		for (UserPermission child : children) {
			// 递归收集子节点的子孙
			ids.addAll(collectDescendantIds(child.getId()));
		}
		return ids;
	}

	/**
	 * 启用 / 禁用权限
	 */
	public boolean toggleStatus(Integer id, Integer status) {
		if (!Arrays.asList(0, 1).contains(status)) {
			throw new IllegalArgumentException("状态值只能是 0 或 1");
		}
		UserPermission perm = new UserPermission();
		perm.setId(id);
		perm.setStatus(status);
		return this.updateById(perm);
	}
}