package com.youlxb2008.admin.modules.objective.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 目标部分更新请求（M4.1，PATCH）
 * <p>
 * PATCH /api/agent/objectives/{oid}（agent 域）/ PATCH /api/objective/admin/objectives/{oid}（管理端）。
 * title/period/targetDate/status/descMd 均可空（=不修改该项）；「至少一项非空」由 service 层判断（全空 → 400）。
 * descMd 提供则覆写详情 md 的「描述」段并重算 progress；status 改「已完成」等不强制关子项。
 */
@Data
public class ObjectivePatchReq {

	/** 目标标题（可选，≤200 字符） */
	private String title;

	/** 周期（可选） */
	private String period;

	/** 截止日期 yyyy-MM-dd（可选） */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date targetDate;

	/** 状态：0未开始 1进行中 2已完成 3已放弃（可选） */
	private Integer status;

	/** 详情「描述」段 markdown 覆写（可选；提供则重写文件「描述」段 + 重算 progress） */
	private String descMd;
}
