package com.youlxb2008.admin.utils;

import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.system.entity.User;
import com.youlxb2008.admin.utils.jwt.context.AuthContext;
import com.youlxb2008.admin.utils.jwt.context.UserContext;

/**
 * 会话工具类，获取当前登录用户及权限信息
 * 替代 Shiro 的 SecurityUtils.getSubject()
 * 底层使用 UserContext（ThreadLocal）存储认证上下文
 */
public class SessionUtils {

    /**
     * 获取当前登录用户
     * @return 当前登录的用户对象
     * @throws BusinessException 如果用户未登录
     */
    public static User getCurrentUser() {
        User user = UserContext.getUser();
        if (user == null) {
            throw new BusinessException("当前用户不存在");
        }
        return user;
    }

    /**
     * 获取当前认证上下文（包含用户、角色、权限）
     * @return 认证上下文
     * @throws BusinessException 如果用户未登录
     */
    public static AuthContext getAuthContext() {
        AuthContext context = UserContext.get();
        if (context == null || context.getUser() == null) {
            throw new BusinessException("当前用户不存在");
        }
        return context;
    }
}
