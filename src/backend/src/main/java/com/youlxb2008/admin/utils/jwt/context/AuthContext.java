package com.youlxb2008.admin.utils.jwt.context;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.youlxb2008.admin.modules.system.entity.User;

import lombok.Data;

/**
 * 用户认证上下文，基于 ThreadLocal 存储当前登录用户的完整信息
 * 包含用户实体、角色标识集合、权限标识集合
 * 替代 Shiro 的 SecurityUtils.getSubject() + PrincipalCollection
 */
@Data
public class AuthContext {

    /** 当前登录用户 */
    private User user;

    /** 用户角色标识集合，如 {"超级管理员", "普通管理员"} */
    private Set<String> roles = Collections.emptySet();

    /** 用户权限标识集合，如 {"user:view", "user:create", "user:update", "user:delete"} */
    private Set<String> permissions = Collections.emptySet();

    public AuthContext(User user) {
        this.user = user;
    }

    public AuthContext(User user, Set<String> roles, Set<String> permissions) {
        this.user = user;
        this.roles = roles != null ? roles : Collections.emptySet();
        this.permissions = permissions != null ? permissions : Collections.emptySet();
    }

    /** 判断是否拥有指定角色 */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /** 判断是否拥有指定权限 */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** 判断是否拥有全部指定角色 */
    public boolean hasAllRoles(Set<String> requiredRoles) {
        return roles.containsAll(requiredRoles);
    }

    /** 判断是否拥有全部指定权限 */
    public boolean hasAllPermissions(Set<String> requiredPermissions) {
        return permissions.containsAll(requiredPermissions);
    }

    /** 判断是否拥有任意一个指定角色 */
    public boolean hasAnyRole(Set<String> requiredRoles) {
        for (String role : requiredRoles) {
            if (roles.contains(role)) return true;
        }
        return false;
    }

    /** 判断是否拥有任意一个指定权限 */
    public boolean hasAnyPermission(Set<String> requiredPermissions) {
        for (String permission : requiredPermissions) {
            if (permissions.contains(permission)) return true;
        }
        return false;
    }
}
