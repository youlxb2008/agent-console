package com.youlxb2008.admin.utils.jwt.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当前登录用户注入注解
 * <p>
 * 标注在 Controller 方法参数上，Spring MVC 自动从 ThreadLocal（UserContext）注入当前登录用户。
 * 未登录时抛出 UnauthorizedException（401），无需手动调用 SessionUtils。
 *
 * <pre>
 * // 用法示例
 * {@code @PostMapping("/create")}
 * public ResultVO create(@CurrentUser User currentUser, {@code @RequestBody} Notice notice) {
 *     notice.setPublisher(currentUser.getUsername());
 *     ...
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
