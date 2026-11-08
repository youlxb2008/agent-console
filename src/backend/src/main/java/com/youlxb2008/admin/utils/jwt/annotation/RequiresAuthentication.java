package com.youlxb2008.admin.utils.jwt.annotation;

/**
 * 自定义认证注解，替代 Shiro 的 @RequiresAuthentication
 * 标注此注解的接口需要用户已认证（已登录）才能访问
 */
@java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE})
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface RequiresAuthentication {
}
