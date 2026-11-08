package com.youlxb2008.admin.modules.note.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * agent 域：写/覆写笔记请求（v3.5：新增 notebookId 必填，D1）
 * <p>
 * v3 智能覆写语义：按 (dirPath, title) 查锚点——未找到=新建；
 * 找到且当前 agent 已关联=覆写（保留原 nid）；找到但未关联=新建（不覆写他人笔记）。
 * v3.5 D5 校验链：notebookId 必填、笔记本存在且未软删（404）、当前 agent 已关联（403）。
 * dirPath/title 黑名单等安全校验由 NoteStorageManager 执行。
 */
@Data
public class NoteWriteReq {

	/** 所属笔记本 id（v3.5 必填；agent 必须已关联否则 403） */
	@NotNull(message = "notebookId 不能为空")
	private Long notebookId;

	/** 目录（可为空串 = 根目录；'/'-分隔，层深 ≤10，单段 ≤64 字符） */
	private String dirPath;

	/** 笔记标题（智能覆写锚点之一，≤200 字符） */
	@NotBlank(message = "标题不能为空")
	private String title;

	/** Markdown 正文（≤2MB） */
	private String contentMd;

	/** 逗号分隔标签（可选，仅落索引） */
	private String tags;
}
