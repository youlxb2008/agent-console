package com.youlxb2008.admin.modules.agent.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * agent 域：写入记忆请求（M7.1，POST /api/agent/memories）
 * <p>
 * memType/source 白名单校验在 Service 层做（400）；
 * status/weight/accessCount 等生命周期字段由服务端管理，不收请求侧；
 * weightFloor 为权重保底线（M7.1-M3a：可空默认 0，0-100 越界 400）。
 */
@Data
public class MemoryCreateReq {

	/** 记忆类型：fact/decision/preference/lesson/event（白名单五值） */
	@NotBlank(message = "memType 不能为空")
	@Size(max = 16, message = "memType 最长 16 字符")
	private String memType;

	/** 来源：dialogue/self/system（白名单三值） */
	@NotBlank(message = "source 不能为空")
	@Size(max = 16, message = "source 最长 16 字符")
	private String source;

	/** 一句话摘要 */
	@NotBlank(message = "title 不能为空")
	@Size(max = 255, message = "title 最长 255 字符")
	private String title;

	/** 结论正文 */
	@NotBlank(message = "contentMd 不能为空")
	private String contentMd;

	/** 逗号分隔标签（可空；服务端规范化去重，≤512 字符） */
	@Size(max = 512, message = "tags 最长 512 字符")
	private String tags;

	/** AC 笔记深链指针（可空） */
	@Size(max = 512, message = "refUrl 最长 512 字符")
	private String refUrl;

	/** 权重保底线（可空默认 0；0-100，越界 400；衰减重算取 max(公式值, weightFloor)） */
	@Min(value = 0, message = "weightFloor 须在 0-100 内")
	@Max(value = 100, message = "weightFloor 须在 0-100 内")
	private Integer weightFloor;
}
