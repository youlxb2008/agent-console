package com.youlxb2008.admin.utils.jwt.annotation;

/**
 * 逻辑枚举，用于 @RequiresPermissions 和 @RequiresRoles 注解
 * 指定多个权限/角色之间的逻辑关系
 */
public enum Logical {

    /** 需要拥有全部指定的权限/角色 */
    AND,

    /** 只需拥有任意一个指定的权限/角色 */
    OR
}
