package com.youlxb2008.admin.modules.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * agent 域 scope 校验注解（契约 api.md §0/§1）
 * <p>
 * 标注在 agent 域 Controller 方法上，由 {@code ScopeAspect} 校验当前
 * API Key 的 scopes 是否包含所需 scope；不满足抛 403（ForbiddenException）
 * 并写审计 denied。
 *
 * <pre>
 * {@code @RequireScope(value = "notes:write", action = "note.write")}
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireScope {

	/** 所需 scope，如 notes:write / audit:read */
	String value();

	/** 审计 action（如 note.write / note.submit / audit.list） */
	String action();
}
