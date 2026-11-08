package com.youlxb2008.admin.modules.note.vo;

import java.util.Date;

import lombok.Data;

/**
 * agent 域 upsert 笔记响应（v3 迁入 note 域，字段调整）
 * <p>
 * 返回 {nid, notebookId, dirPath, title, updateTime}：覆写返回原 nid（不变），
 * 新建返回新 nid（供 agent 核对被覆写/新建对象，§8 同名歧义对策）。
 */
@Data
public class NoteUpsertVO {

	/** 对外引用键；索引写失败（孤儿文件）时为 null */
	private String nid;

	/** 所属笔记本 id（v3.5，写/移动语义落库的目标笔记本） */
	private Long notebookId;

	/** 目录路径（根目录为 ""） */
	private String dirPath;

	/** 笔记标题 */
	private String title;

	/** 本次写入时间 */
	private Date updateTime;

	public NoteUpsertVO() {
	}

	public NoteUpsertVO(String nid, Long notebookId, String dirPath, String title, Date updateTime) {
		this.nid = nid;
		this.notebookId = notebookId;
		this.dirPath = dirPath;
		this.title = title;
		this.updateTime = updateTime;
	}
}
