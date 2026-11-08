package com.youlxb2008.admin.modules.todo.dto;

import lombok.Data;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 待办部分更新请求（M5.2，PATCH；M5.3 补 dueDate）
 * <p>
 * PATCH /api/agent/todos/{todoId}（agent 域）。
 * title/content/status/priority/tags/dueDate 均可空（=不修改该项）；
 * 「至少一项非空」由 service 层判断（全空 → 400）。
 * status 任意流转不做强校验（todolist 场景灵活优先）。
 * dueDate 注解写法照抄 ObjectivePatchReq.targetDate（java.util.Date + @JsonFormat yyyy-MM-dd）。
 */
@Data
public class TodoPatchReq {

	/** 标题（可选，≤200 字符） */
	private String title;

	/** md 正文（可选） */
	private String content;

	/** 状态：0待办 1进行中 2已完成 3已放弃（可选） */
	private Integer status;

	/** 优先级 1-5（可选） */
	private Integer priority;

	/** 逗号分隔标签（可选） */
	private String tags;

	/** 截止日期 yyyy-MM-dd（可选；null = 不修改） */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date dueDate;
}
