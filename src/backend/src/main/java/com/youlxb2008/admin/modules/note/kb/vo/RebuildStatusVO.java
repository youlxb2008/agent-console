package com.youlxb2008.admin.modules.note.kb.vo;

import lombok.Data;

/**
 * KB 全量重建进度 VO（管理端轮询）
 */
@Data
public class RebuildStatusVO {
	/** 是否重建中（true=进行中，前端继续轮询） */
	private boolean rebuilding;
	/** 本批总笔记数 */
	private int total;
	/** 已完成数（含失败） */
	private int done;
	/** 失败数 */
	private int failed;
	/** 结束时的汇总文案（进行中为空） */
	private String message;
	/** 百炼 API Key 是否已配置（false 时无向量检索/问答，前端给配置引导） */
	private boolean keyConfigured;
	/** Lucene 全文索引是否可用 */
	private boolean luceneAvailable;
}
