package com.youlxb2008.admin.utils.jwt.context;

import com.youlxb2008.admin.modules.system.entity.User;

/**
 * 用户上下文工具类，基于 ThreadLocal 存储当前登录用户信息
 * 替代 Shiro 的 SecurityUtils.getSubject().getPrincipal()
 */
public class UserContext {

    /** ThreadLocal 存储当前线程的认证上下文 */
    private static final ThreadLocal<AuthContext> CONTEXT_HOLDER = new ThreadLocal<>();

    /**
     * 设置认证上下文
     * @param context 认证上下文（包含用户、角色、权限）
     */
    public static void set(AuthContext context) {
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取当前认证上下文
     * @return 认证上下文，未登录时返回 null
     */
    public static AuthContext get() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取当前登录用户
     * @return 当前用户，未登录时返回 null
     */
    public static User getUser() {
        AuthContext context = CONTEXT_HOLDER.get();
        return context != null ? context.getUser() : null;
    }

    /**
     * 清除当前线程的认证上下文，防止内存泄漏
     * 必须在请求结束后调用
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
