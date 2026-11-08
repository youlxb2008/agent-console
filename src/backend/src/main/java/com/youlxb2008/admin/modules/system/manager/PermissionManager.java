package com.youlxb2008.admin.modules.system.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.RoleOnlyEntity;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.entity.UserRole;
import com.youlxb2008.admin.modules.system.entity.UserRolePermission;
import com.youlxb2008.admin.modules.system.mapper.UserMapper;
import com.youlxb2008.admin.modules.system.service.RoleOnlyService;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;
import com.youlxb2008.admin.modules.system.service.UserRolePermissionService;
import com.youlxb2008.admin.modules.system.service.UserRoleService;
import com.youlxb2008.admin.modules.system.service.UserService;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import lombok.extern.slf4j.Slf4j;

/**
 * 权限聚合 Manager
 * 负责根据用户聚合查询角色与权限，以及填充用户角色列表
 */
@Slf4j
@Component
public class PermissionManager {

	@Autowired
	private UserService userService;

	@Autowired
	private UserMapper userMapper;

	@Autowired
	private UserRoleService userRoleService;

	@Autowired
	private UserRolePermissionService rolePermissionService;

	@Autowired
	private UserPermissionService permissionService;

	@Autowired
	private RoleOnlyService roleService;

	/**
	 * 根据用户名查询该用户拥有的角色列表
	 */
	public List<RoleOnlyEntity> getRolesByUsername(String username) {
		List<RoleOnlyEntity> roles = new ArrayList<>();
		User user = userService.getOne(
				Wrappers.<User>lambdaQuery().eq(User::getUsername, username), true);
		if (null != user) {
			List<UserRole> userRoles = userRoleService
					.list(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, user.getId()));
			if (CollUtil.isNotEmpty(userRoles)) {
				List<Integer> roleIds = userRoles.stream()
						.map(UserRole::getRoleId)
						.collect(Collectors.toList());
				roles = roleService.listByIds(roleIds);
			}
		}
		return roles;
	}

	/**
	 * 根据用户名查询该用户拥有的权限列表
	 * 超级管理员（userType=0）直接返回所有权限，绕过角色-权限查询
	 */
	public List<UserPermission> getPermissionsByUsername(String username) {
		List<UserPermission> permissions = new ArrayList<>();
		User user = userService.getOne(
				Wrappers.<User>lambdaQuery().eq(User::getUsername, username), true);
		if (null != user) {
			// 超级管理员直接返回所有权限
			if (user.getUserType() != null && user.getUserType() == User.UserType.ADMIN.getValue()) {
				return permissionService.list();
			}
			permissions = getPermissionsByUserId(user.getId());
		}
		return permissions;
	}

	/**
	 * 根据 userId 查询权限标识集合（供 JwtFilter 每次请求时填充 AuthContext.permissions）
	 * <p>
	 * 查询链路：user_id → t_user_role → role_id → t_role_permission → permission_id →
	 * t_permission → permission_key
	 * <p>
	 * 此方法是权限查询的统一入口，未来如需加缓存或改 SQL，只改这里即可。
	 *
	 * @param userId 用户 ID
	 * @return 权限标识集合（如 {"system:user", "system:role"}），无权限返回空集
	 */
	public List<UserPermission> getPermissionsByUserId(Long userId) {
		List<UserPermission> permissions = new ArrayList<>();
		List<UserRole> userRoles = userRoleService
				.list(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
		if (CollUtil.isNotEmpty(userRoles)) {
			List<Integer> roleIds = new ArrayList<>();
			userRoles.stream().forEach(userRole -> {
				roleIds.add(userRole.getRoleId());
			});
			List<UserRolePermission> rolePermissions = rolePermissionService
					.list(Wrappers.<UserRolePermission>lambdaQuery().in(UserRolePermission::getRoleId, roleIds));
			if (CollUtil.isNotEmpty(rolePermissions)) {
				List<Integer> permissionIds = new ArrayList<>();
				rolePermissions.stream().forEach(rolePermission -> {
					permissionIds.add(rolePermission.getPermissionId());
				});
				permissions = permissionService
						.list(Wrappers.<UserPermission>lambdaQuery().in(UserPermission::getId, permissionIds));
			}
		}
		return permissions;
	}

	/**
	 * 根据 userId 查询权限标识集合（String 形式，供 JwtFilter 直接装入 AuthContext）
	 *
	 * @param userId 用户 ID
	 * @return 权限标识字符串集合
	 */
	public Set<String> getPermissionKeysByUserId(Long userId) {
		List<UserPermission> permissions = getPermissionsByUserId(userId);
		return permissions.stream()
				.map(UserPermission::getPermissionKey)
				.filter(k -> k != null && !k.isEmpty())
				.collect(Collectors.toCollection(HashSet::new));
	}

	/**
	 * 根据 userId 查询角色名称集合（供 JwtFilter 填充 AuthContext.roles）
	 *
	 * @param userId 用户 ID
	 * @return 角色名称集合
	 */
	public Set<String> getRoleNamesByUserId(Long userId) {
		List<UserRole> userRoles = userRoleService
				.list(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
		if (CollUtil.isEmpty(userRoles)) {
			return new HashSet<>();
		}
		List<Integer> roleIds = userRoles.stream()
				.map(UserRole::getRoleId)
				.collect(Collectors.toList());
		List<RoleOnlyEntity> roles = roleService.listByIds(roleIds);
		return roles.stream()
				.map(RoleOnlyEntity::getName)
				.collect(Collectors.toCollection(HashSet::new));
	}

	/**
	 * 填充用户列表的角色信息
	 * 通过 t_user_role 关联查询每个用户的角色列表
	 */
	public void fillUserRoles(List<User> users) {
		if (users == null || users.isEmpty())
			return;
		for (User user : users) {
			List<UserRole> userRoles = userRoleService.list(
					Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, user.getId()));
			if (!userRoles.isEmpty()) {
				List<Integer> roleIds = userRoles.stream().map(UserRole::getRoleId).collect(Collectors.toList());
				List<RoleOnlyEntity> roles = roleService.listByIds(roleIds);
				user.setRoles(roles);
			}
		}
	}

	/**
	 * 分配用户角色（全量覆盖）
	 * 先删除该用户所有旧的角色关联，再批量插入新的关联记录
	 * 跨实体操作（UserRole），从 UserService 迁移至此
	 *
	 * @param userId  用户ID
	 * @param roleIds 角色ID列表
	 */
	@DSTransactional
	public void assignRoles(Long userId, List<Integer> roleIds) {
		Assert.notNull(userId, "用户ID不能为空");
		User user = userMapper.selectById(userId);
		if (user == null) {
			throw new BusinessException("用户不存在");
		}
		// 先删除该用户所有旧的角色关联
		userRoleService.remove(Wrappers.<UserRole>lambdaQuery().eq(UserRole::getUserId, userId));
		// 再批量插入新的关联记录
		if (CollUtil.isNotEmpty(roleIds)) {
			for (Integer roleId : roleIds) {
				UserRole userRole = new UserRole();
				userRole.setUserId(userId.intValue());
				userRole.setRoleId(roleId);
				userRoleService.save(userRole);
			}
		}
	}
}
