package com.youlxb2008.admin.modules.note.dto;

import lombok.Data;

/**
 * agent 域：笔记挪动/重命名请求（M3.7 新增）
 * <p>
 * PATCH /api/agent/notes/{nid}：按 nid 直接改 meta（dirPath/title/notebookId），
 * 保留 nid 与文件、关联行不动（文件路径 = NOTES_ROOT/notes/{nid}.md 只跟 nid 走）。
 * 三个字段均可空（=不修改该项）；「至少一项非空」由 service 层判断（全空 → 400）。
 */
@Data
public class NotePatchReq {

	/** 目标笔记本 id（可选；移动语义，改则复用 D5 校验：不存在/软删 404、未关联 403） */
	private Long notebookId;

	/** 目标目录路径（可选；空串 = 根目录；'/'-分隔，层深 ≤10，单段 ≤64 字符） */
	private String dirPath;

	/** 新标题（可选，≤200 字符，去 .md 后缀差异） */
	private String title;
}
