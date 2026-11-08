package com.youlxb2008.admin.modules.agent.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * agent 域：更新记忆请求（M7.1，PUT /api/agent/memories/{id}）
 * <p>
 * 全字段可选（null=不修改），至少修改一项否则 400；
 * memType 传入时仍走白名单校验；生命周期字段（status/supersededBy）不收请求侧；
 * weight 支持手动覆盖（M7.1-M4）：set 即覆盖落库值，后续衰减/加成在新值上作用；
 * weightFloor 为权重保底线（M7.1-M3a：null=不修改，0-100 越界 400）。
 */
@Data
public class MemoryUpdateReq {

	/** 一句话摘要（null=不修改） */
	@Size(max = 255, message = "title 最长 255 字符")
	private String title;

	/** 结论正文（null=不修改） */
	private String contentMd;

	/** 记忆类型（null=不修改；传入时白名单校验） */
	@Size(max = 16, message = "memType 最长 16 字符")
	private String memType;

	/** 逗号分隔标签（null=不修改） */
	@Size(max = 512, message = "tags 最长 512 字符")
	private String tags;

	/** AC 笔记深链指针（null=不修改） */
	@Size(max = 512, message = "refUrl 最长 512 字符")
	private String refUrl;

	/** 权重（null=不修改；范围 0-100，越界 400） */
	@Min(0)
	@Max(100)
	private Integer weight;

	/** 权重保底线（null=不修改；范围 0-100，越界 400；0=不保底） */
	@Min(0)
	@Max(100)
	private Integer weightFloor;
}
