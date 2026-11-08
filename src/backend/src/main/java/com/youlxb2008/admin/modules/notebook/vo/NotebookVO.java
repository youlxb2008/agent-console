package com.youlxb2008.admin.modules.notebook.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 笔记本出参 VO（v3.5 新增）
 * <p>
 * 管理端分页/下拉 + agent 关联回显共用：
 * <ul>
 *   <li>page/all：noteCount/agentCount（聚合）；notebookIds/notebookNames 不填</li>
 *   <li>agent 详情回显：notebookIds/notebookNames 填充（笔记本与 agent N:N）</li>
 * </ul>
 */
@Data
public class NotebookVO {

	/** 笔记本 id */
	private Long id;

	/** 笔记本名 */
	private String name;

	/** 简介 */
	private String description;

	/** 封面 URL（可选，列表页横幅封面，宽幅） */
	private String cover;

	/** logo URL（可选，笔记页左上角小标识，方图；v4.17 新增） */
	private String logo;

	/** hero URL（可选，笔记页空态中央展示图，宽幅；v4.17 新增） */
	private String hero;

	/** 状态：1=active 0=hidden */
	private Integer status;

	/** 该笔记本下笔记数（t_note_meta 聚合） */
	private long noteCount;

	/** 该笔记本关联的 agent 数（t_agent_notebook 聚合） */
	private long agentCount;

	/** 该笔记本下笔记最近更新时间（t_note_meta 聚合，门户卡片"最近更新"） */
	private Date lastNoteAt;

	private Date createTime;

	private Date updateTime;

	/** agent 详情回显：该 agent 关联的笔记本 id 集合 */
	private List<Long> notebookIds;

	/** agent 详情回显：该 agent 关联的笔记本名集合（与 notebookIds 对齐） */
	private List<String> notebookNames;
}