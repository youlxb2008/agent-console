package com.youlxb2008.admin.modules.note.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 笔记索引实体（v3：无主化，迁入 note 域）
 * 对应数据库表 t_note_meta（docs/版本/agent-v3_20260830 §2.2）
 * <p>
 * 纯索引表：真相源在 NOTES_ROOT/notes/{nid}.md 文件系统，路径由 nid 派生不再入库。
 * v3 变更：🔥 删 agentId/filePath（署名走 t_agent_resource N:N 关联），
 * ✨ 增 dirPath（目录树依据）。
 * 物理删除模型（无 is_deleted，无 status/visibility/review 字段）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_note_meta")
public class NoteMeta {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（纯内部，不外露） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 对外引用键：n_ + 10 位 base62，随机生成终身不变；覆写保留原 nid */
	private String nid;

	/** 所属笔记本 id（v3.5 D1：笔记直接挂笔记本，写笔记时 agent 须指定） */
	private Long notebookId;

	/** 目录路径（树依据），如 工作日志/2026-08；根目录为空串 */
	private String dirPath;

	/** 笔记标题（智能覆写锚点之一：(dirPath, title)），管理端搜索字段 */
	private String title;

	/** 逗号分隔标签 */
	private String tags;

	/** 首次写入时间 */
	private Date createTime;

	/** 最近覆写时间 */
	private Date updateTime;

	/** 笔记本名（非表字段：管理端/树回填用，来自 t_notebook 联查） */
	@TableField(exist = false)
	private String notebookName;
}
