package com.youlxb2008.admin.modules.objective.dto;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * agent 域/管理端：新建目标请求（M4.1）
 * <p>
 * POST /api/agent/objectives（agent 域，scope objective:write）/ POST /api/objective/admin/objectives（管理端）。
 * title 必填；period（2026-Q4/2026/自由文本）与 targetDate（yyyy-MM-dd）可选；
 * descMd = 详情「描述」段 markdown（可空，空则写空模板占位）。子项由 agent 后续直接改 md 维护。
 */
@Data
public class ObjectiveCreateReq {

	/** 目标标题（todolist 条目，必填，≤200 字符） */
	@NotBlank(message = "标题不能为空")
	private String title;

	/** 周期：2026-Q4 / 2026 / 自由文本（可选） */
	private String period;

	/** 截止日期 yyyy-MM-dd（可选） */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date targetDate;

	/** 详情「描述」段 markdown（可空；空则模板占位） */
	private String descMd;
}
