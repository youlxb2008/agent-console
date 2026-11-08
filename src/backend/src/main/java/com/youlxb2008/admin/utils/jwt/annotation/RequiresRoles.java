package com.youlxb2008.admin.utils.jwt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色校验注解，替代 Shiro 的 @RequiresRoles
 * 标注此注解的接口需要用户拥有指定角色才能访问
 *
 * logical: 角色之间的逻辑关系
 * - Logical.AND（默认）：需要拥有所有指定角色
 * - Logical.OR：只需拥有任意一个指定角色
 *
 * 示例：
 * @RequiresRoles("超级管理员")                                    // 需要超级管理员角色
 * @RequiresRoles({"超级管理员", "普通管理员"}, logical = Logical.OR) // 拥有任一角色即可
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRoles {

    /** 需要的角色名称 */
    String[] value();

    /** 角色之间的逻辑关系，默认 AND（需要全部拥有） */
    Logical logical() default Logical.AND;
}
