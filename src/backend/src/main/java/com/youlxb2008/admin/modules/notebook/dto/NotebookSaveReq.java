package com.youlxb2008.admin.modules.notebook.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 新建/编辑笔记本请求（v3.5 新增）
 * <p>
 * name 唯一（未软删范围内，编辑时排除自身）；status 缺省 1=active。
 */
@Data
public class NotebookSaveReq {

	/** 笔记本 id（编辑(PUT)必填；新建(POST)不填） */
	private Long id;

	/** 笔记本名（未软删范围内唯一） */
	@NotBlank(message = "笔记本名不能为空")
	private String name;

	/** 简介（可选） */
	private String description;

	/** 封面 URL（可选，列表页横幅封面，宽幅） */
	private String cover;

	/** logo URL（可选，笔记页左上角小标识，方图；v4.17 新增） */
	private String logo;

	/** hero URL（可选，笔记页空态中央展示图，宽幅；v4.17 新增） */
	private String hero;

	/** 状态：1=active 0=hidden，null 时默认 1 */
	private Integer status;
}