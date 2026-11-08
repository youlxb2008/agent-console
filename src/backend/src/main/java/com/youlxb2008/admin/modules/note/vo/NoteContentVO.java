package com.youlxb2008.admin.modules.note.vo;

import java.util.Date;
import java.util.List;

import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;

import lombok.Data;

/**
 * 笔记内容响应（v3 迁入 note 域；门户与管理端预览共用）
 * <p>
 * nid 定位读取文件系统真相源（NOTES_ROOT/notes/{nid}.md）。
 * v3：✨ 增 contributors（全部关联 agent，门户详情页/管理端预览贡献者共用）；
 * 孤儿文件概念消失（无索引行的文件不可见），孤儿索引行（有行无文件）读内容时 404。
 */
@Data
public class NoteContentVO {

	/** 对外引用键 n_xxx */
	private String nid;

	/** 标题（索引 title） */
	private String title;

	/** Markdown 原文 */
	private String contentMd;

	/** 所在目录路径（索引 dir_path，根目录为 ""） */
	private String dirPath;

	/** 最近更新时间（索引 update_time） */
	private Date updateTime;

	/** 文件字节数 */
	private Long sizeBytes;

	/** 贡献者（全部关联 agent；NoteStorageManager.readContent 统一填充） */
	private List<AgentSimpleVO> contributors;
}
