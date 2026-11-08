package com.youlxb2008.admin.modules.note.vo;

import java.util.Date;
import java.util.List;

import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;

import lombok.Data;

/**
 * 管理端：笔记列表项（v3 迁入 note 域，字段调整）
 * <p>
 * v3：🔥 移除 agentId/agentName 单值与 filePath（笔记无主化，署名走关联表）；
 * ✨ 增 dirPath / tags / contributors[]（全部关联 agent，贡献者多标签渲染）。
 */
@Data
public class AdminNoteVO {

	/** 对外引用键 n_xxx */
	private String nid;

	/** 所属笔记本 id（v3.5） */
	private Long notebookId;

	/** 所属笔记本名（v3.5，联查 t_notebook 回填） */
	private String notebookName;

	/** 笔记标题 */
	private String title;

	/** 目录路径（树依据，根目录为 ""） */
	private String dirPath;

	/** 逗号分隔标签 */
	private String tags;

	/** 贡献者（全部关联 agent；列表渲染多个 tag，超 3 个显示 +N） */
	private List<AgentSimpleVO> contributors;

	/** 最近更新时间 */
	private Date updateTime;
}
