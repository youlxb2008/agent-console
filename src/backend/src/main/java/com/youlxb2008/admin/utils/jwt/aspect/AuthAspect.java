package com.youlxb2008.admin.utils.jwt.aspect;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.UnauthorizedException;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.jwt.annotation.Logical;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresAuthentication;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresRoles;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresUser;
import com.youlxb2008.admin.utils.jwt.context.AuthContext;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

/**
 * 认证授权 AOP 切面，拦截自定义权限注解
 * 替代 Shiro 的 AuthorizationAttributeSourceAdvisor
 * 支持 @RequiresUser、@RequiresAuthentication、@RequiresPermissions、@RequiresRoles
 */
@Aspect
@Component
public class AuthAspect {

    /**
     * 拦截 @RequiresUser 注解，校验用户是否已登录
     */
    @Around("@annotation(com.youlxb2008.admin.utils.jwt.annotation.RequiresUser)")
    public Object checkRequiresUser(ProceedingJoinPoint joinPoint) throws Throwable {
        AuthContext context = UserContext.get();
        if (context == null || context.getUser() == null) {
            throw new UnauthorizedException("未登录或登录失效，请重新登录");
        }
        return joinPoint.proceed();
    }

    /**
     * 拦截 @RequiresAuthentication 注解，校验用户是否已认证
     */
    @Around("@annotation(com.youlxb2008.admin.utils.jwt.annotation.RequiresAuthentication)")
    public Object checkRequiresAuthentication(ProceedingJoinPoint joinPoint) throws Throwable {
        AuthContext context = UserContext.get();
        if (context == null || context.getUser() == null) {
            throw new UnauthorizedException("未登录或登录失效，请重新登录");
        }
        return joinPoint.proceed();
    }

    /**
     * 拦截 @RequiresPermissions 注解，校验用户是否拥有指定权限
     * 支持 AND（全部拥有）和 OR（任一拥有）逻辑
     * 超级管理员（userType=0）直接放行
     */
    @Around("@annotation(requiresPermissions)")
    public Object checkRequiresPermissions(ProceedingJoinPoint joinPoint,
                                           RequiresPermissions requiresPermissions) throws Throwable {
        // 先校验登录
        AuthContext context = checkLogin();

        // 超级管理员直接放行，拥有所有权限
        User user = context.getUser();
        if (user != null && user.getUserType() != null
                && user.getUserType() == User.UserType.ADMIN.getValue()) {
            return joinPoint.proceed();
        }

        // 获取需要的权限集合
        Set<String> requiredPermissions = new HashSet<>(Arrays.asList(requiresPermissions.value()));
        Logical logical = requiresPermissions.logical();

        // 根据逻辑关系校验权限
        boolean hasPermission;
        if (logical == Logical.AND) {
            // AND: 需要拥有全部权限
            hasPermission = context.hasAllPermissions(requiredPermissions);
        } else {
            // OR: 只需拥有任一权限
            hasPermission = context.hasAnyPermission(requiredPermissions);
        }

        if (!hasPermission) {
            String msg = logical == Logical.AND
                    ? "需要权限: " + String.join(", ", requiredPermissions)
                    : "需要权限（任一）: " + String.join(", ", requiredPermissions);
            throw new ForbiddenException("权限不足，" + msg);
        }

        return joinPoint.proceed();
    }

    /**
     * 拦截 @RequiresRoles 注解，校验用户是否拥有指定角色
     * 支持 AND（全部拥有）和 OR（任一拥有）逻辑
     * 超级管理员（userType=0）直接放行
     */
    @Around("@annotation(requiresRoles)")
    public Object checkRequiresRoles(ProceedingJoinPoint joinPoint,
                                     RequiresRoles requiresRoles) throws Throwable {
        // 先校验登录
        AuthContext context = checkLogin();

        // 超级管理员直接放行
        User user = context.getUser();
        if (user != null && user.getUserType() != null
                && user.getUserType() == User.UserType.ADMIN.getValue()) {
            return joinPoint.proceed();
        }

        // 获取需要的角色集合
        Set<String> requiredRoles = new HashSet<>(Arrays.asList(requiresRoles.value()));
        Logical logical = requiresRoles.logical();

        // 根据逻辑关系校验角色
        boolean hasRole;
        if (logical == Logical.AND) {
            // AND: 需要拥有全部角色
            hasRole = context.hasAllRoles(requiredRoles);
        } else {
            // OR: 只需拥有任一角色
            hasRole = context.hasAnyRole(requiredRoles);
        }

        if (!hasRole) {
            String msg = logical == Logical.AND
                    ? "需要角色: " + String.join(", ", requiredRoles)
                    : "需要角色（任一）: " + String.join(", ", requiredRoles);
            throw new ForbiddenException("角色不足，" + msg);
        }

        return joinPoint.proceed();
    }

    /**
     * 检查用户是否已登录
     * @return 认证上下文
     * @throws UnauthorizedException 未登录时抛出
     */
    private AuthContext checkLogin() {
        AuthContext context = UserContext.get();
        if (context == null || context.getUser() == null) {
            throw new UnauthorizedException("未登录或登录失效，请重新登录");
        }
        return context;
    }
}
