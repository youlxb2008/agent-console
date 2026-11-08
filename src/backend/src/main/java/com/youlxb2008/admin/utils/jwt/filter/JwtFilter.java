package com.youlxb2008.admin.utils.jwt.filter;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.modules.system.entity.UserPermission;
import com.youlxb2008.admin.modules.system.manager.PermissionManager;
import com.youlxb2008.admin.modules.system.service.UserPermissionService;
import com.youlxb2008.admin.modules.system.service.UserService;
import com.youlxb2008.admin.utils.jwt.JwtConstants;
import com.youlxb2008.admin.utils.jwt.context.AuthContext;
import com.youlxb2008.admin.utils.jwt.context.UserContext;
import com.youlxb2008.admin.utils.jwt.utils.JwtUtil;

import io.jsonwebtoken.Claims;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * JWT 认证过滤器
 * 继承 Spring 的 OncePerRequestFilter，每个请求只过滤一次
 * <p>
 * 删除 Spring Security 后，由 Spring Boot 自动注册为 Servlet Filter。
 *
 * 核心流程：
 * 1. 从请求 Header 中获取 Authorization token
 * 2. 解析并验证 token 有效性
 * 3. 通过 PermissionManager 查询用户角色、权限，存入 AuthContext（ThreadLocal）
 * 4. 请求结束后清理 AuthContext，防止内存泄漏
 */
@Slf4j
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private UserService userService;

    /** 权限聚合查询统一入口（角色、权限均由 PermissionManager 提供） */
    @Resource
    private PermissionManager permissionManager;

    /** 超级管理员场景下一次性拉取所有权限标识，避免在 PermissionManager 里硬编码 userType 分支 */
    @Resource
    private UserPermissionService permissionService;

    /** 登录接口路径，不需要 JWT 认证（统一引用 {@link JwtConstants}） */
    private static final String LOGIN_PATH = JwtConstants.LOGIN_PATH;
    /** 刷新 token 接口路径 */
    private static final String REFRESH_TOKEN_PATH = JwtConstants.REFRESH_TOKEN_PATH;
    /** 登出接口路径 */
    private static final String LOGOUT_PATH = JwtConstants.LOGOUT_PATH;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            // 登录、刷新 token、登出接口不需要 JWT 认证
            String requestURI = request.getRequestURI();
            if (isExcludedPath(requestURI)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 从 Header 中获取 token
            String token = request.getHeader(JwtUtil.HEADER);
            if (token != null && !token.isEmpty()) {
                // 兼容 Bearer 前缀（前端约定不加，但保留兼容代码提升健壮性）
                if (token.startsWith("Bearer")) {
                    token = token.replaceFirst("Bearer", "").trim();
                }

                // 解析 token 并构建认证上下文
                AuthContext authContext = buildAuthContext(token);
                if (authContext != null) {
                    // 将认证上下文存入 ThreadLocal
                    UserContext.set(authContext);
                }
            }

            // 继续过滤器链，由 @RequiresUser/@RequiresPermissions 等注解控制权限
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束后清理 ThreadLocal，防止内存泄漏
            UserContext.clear();
        }
    }

    /**
     * 解析 JWT token 并构建完整的认证上下文（用户 + 角色 + 权限）
     * <p>
     * 角色、权限查询统一委托给 {@link PermissionManager}，避免本类维护多表 JOIN 链路。
     *
     * @param token JWT token 字符串
     * @return 认证上下文，认证失败返回 null
     */
    private AuthContext buildAuthContext(String token) {
        try {
            // 1. 解析 JWT 获取用户名
            Claims claims = jwtUtil.getClaimsByToken(token);
            String username = claims.getSubject();

            // 2. 检查 token 是否过期
            if (jwtUtil.isTokenExpired(claims.getExpiration())) {
                log.warn("JWT token 已过期, username={}", username);
                return null;
            }

            // 3. 查询用户
            User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
            if (user == null) {
                log.warn("JWT token 中用户不存在, username={}", username);
                return null;
            }
            // 4. 检查用户是否被锁定
            if (user.getStatus() != null && user.getStatus() != 0) {
                log.warn("用户已被锁定, username={}", username);
                return null;
            }

            // 5. 查询角色与权限（通过 PermissionManager 统一入口）
            Set<String> roleNames;
            Set<String> permissionKeys;
            if (user.getUserType() != null && user.getUserType() == User.UserType.ADMIN.getValue()) {
                // 超级管理员（userType=0）直接拥有所有权限和超级管理员角色，绕过角色-权限查询
                roleNames = new HashSet<>();
                roleNames.add("超级管理员");
                permissionKeys = permissionService.list().stream()
                        .map(UserPermission::getPermissionKey)
                        .filter(k -> k != null && !k.isEmpty())
                        .collect(java.util.stream.Collectors.toSet());
            } else {
                // 普通用户：按 userId 查询角色名和权限标识
                roleNames = permissionManager.getRoleNamesByUserId(user.getId());
                permissionKeys = permissionManager.getPermissionKeysByUserId(user.getId());
            }

            return new AuthContext(user, roleNames, permissionKeys);
        } catch (Exception e) {
            log.error("JWT token 认证失败: ", e);
            return null;
        }
    }

    /**
     * 判断请求路径是否不需要 JWT 认证
     *
     * @param requestURI 请求路径
     * @return true 表示跳过认证
     */
    private boolean isExcludedPath(String requestURI) {
        return LOGIN_PATH.equals(requestURI)
                || REFRESH_TOKEN_PATH.equals(requestURI)
                || LOGOUT_PATH.equals(requestURI);
    }
}
