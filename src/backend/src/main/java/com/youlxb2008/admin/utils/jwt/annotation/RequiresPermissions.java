package com.youlxb2008.admin.utils.jwt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验注解，替代 Shiro 的 @RequiresPermissions
 * 标注此注解的接口需要用户拥有指定权限才能访问
 *
 * logical: 权限之间的逻辑关系
 * - Logical.AND（默认）：需要拥有所有指定权限
 * - Logical.OR：只需拥有任意一个指定权限
 *
 * 示例：
 * @RequiresPermissions("user:view")                    // 需要 user:view 权限
 * @RequiresPermissions({"user:view", "user:create"})   // 需要同时拥有两个权限
 * @RequiresPermissions(value = {"user:view", "user:create"}, logical = Logical.OR)  // 拥有任一即可
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermissions {

    /** 需要的权限标识，如 "user:view" */
    String[] value();

    /** 权限之间的逻辑关系，默认 AND（需要全部拥有） */
    Logical logical() default Logical.AND;
}
