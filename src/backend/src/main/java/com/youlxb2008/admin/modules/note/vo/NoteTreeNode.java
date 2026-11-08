package com.youlxb2008.admin.modules.note.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 目录树节点（v3 迁入 note 域；数据驱动：t_agent_resource 过滤 + dir_path 内存聚合）
 * <p>
 * <ul>
 *   <li>folder 节点：{name, type="folder", children[]}（无 updateTime）</li>
 *   <li>note 节点：{nid, name, type="note", updateTime}</li>
 * </ul>
 * 排序：folder 字典序在前、note 字典序在后。
 * v3：🔥 删 path 字段（文件扁平存储，nid 即唯一定位键，无兜底读路径）。
 */
@Data
public class NoteTreeNode {

	/** 节点名：folder=目录段名；note=笔记标题 */
	private String name;

	/** 节点类型：folder / note */
	private String type;

	/** note 专属：对外引用键 n_xxx */
	private String nid;

	/** note 专属：所属笔记本 id（v3.5，前端按 notebook 分组渲染） */
	private Long notebookId;

	/** note 专属：所属笔记本名（v3.5，前端按 notebook 分组渲染） */
	private String notebookName;

	/** note 专属：最近更新（索引 update_time）；folder 节点不填 */
	private Date updateTime;

	/** folder 专属：子节点 */
	private List<NoteTreeNode> children;
}
