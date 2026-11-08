package com.youlxb2008.admin.modules.note.kb.vo;

import java.util.List;

import lombok.Data;

/**
 * 知识库混合检索单条命中 VO（门户搜索结果项）
 */
@Data
public class KbHitVO {
	/** 笔记对外引用键（深链 /notebooks/{notebookId}/notes/{nid}） */
	private String nid;
	/** 所属笔记本 id */
	private Long notebookId;
	/** 笔记标题 */
	private String title;
	/** 目录路径（面包屑展示） */
	private String dirPath;
	/** 命中片段（来自最相关分块，截断 ~160 字；BM25-only 命中时为正文开头） */
	private String snippet;
	/** RRF 融合得分（仅排序参考，回传便于前端调试展示） */
	private double score;
	/** 命中来源集合：title=标题 / bm25=关键词 / vector=语义（前端可打标） */
	private List<String> sources;
}
