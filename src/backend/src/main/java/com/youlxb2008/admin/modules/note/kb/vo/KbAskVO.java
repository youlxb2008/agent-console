package com.youlxb2008.admin.modules.note.kb.vo;

import java.util.List;

import lombok.Data;

/**
 * 知识库 RAG 问答结果 VO
 */
@Data
public class KbAskVO {
	/** 模型生成的答案全文（markdown，前端安全渲染） */
	private String answer;
	/** 引用笔记列表（按相关度降序；点击可跳转原文核对） */
	private List<Reference> references;

	/** 单条引用 */
	@Data
	public static class Reference {
		/** 笔记对外引用键 */
		private String nid;
		/** 所属笔记本 id */
		private Long notebookId;
		/** 笔记标题 */
		private String title;
		/** 目录路径 */
		private String dirPath;
	}
}
