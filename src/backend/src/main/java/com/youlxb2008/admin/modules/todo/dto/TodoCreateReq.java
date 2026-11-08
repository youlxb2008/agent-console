package com.youlxb2008.admin.modules.todo.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * agent 域：新建待办请求（M5.2，M5.3 补 dueDate）
 * <p>
 * POST /api/agent/todos（scope todo:write）。
 * title 必填（≤200，服务层校验）；content/priority/tags/status/dueDate 可选（有默认值或可空）。
 * tags 传逗号分隔字符串，不收 JSON 数组（笔记接口同款坑，勿重蹈）。
 * dueDate 注解写法照抄 ObjectiveCreateReq.targetDate（java.util.Date + @JsonFormat yyyy-MM-dd，禁 java.time）。
 */
@Data
public class TodoCreateReq {

	/** 标题（必填，≤200 字符） */
	@NotBlank(message = "标题不能为空")
	private String title;

	/** md 正文：来源/原因/产出要求（可选） */
	private String content;

	/** 优先级 1-5（可选，默认 3） */
	private Integer priority;

	/** 逗号分隔标签（可选，如 "调研,池子"） */
	private String tags;

	/** 状态：0待办 1进行中 2已完成 3已放弃（可选，默认 0） */
	private Integer status;

	/** 截止日期 yyyy-MM-dd（可选；有值排前，过期标红） */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date dueDate;
}
