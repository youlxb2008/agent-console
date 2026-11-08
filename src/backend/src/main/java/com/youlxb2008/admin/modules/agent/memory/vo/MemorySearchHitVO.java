package com.youlxb2008.admin.modules.agent.memory.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 记忆检索命中项（M7.1，GET /api/agent/memories/search）
 * <p>
 * 归档（status=2）与被取代（status=3）条目参与检索但带 status 标记（任务书 §2.3）；
 * contentMd 返回全文（记忆条目正文短，检索场景需要看到结论）。
 */
@Data
public class MemorySearchHitVO {

	/** 对外引用键（数字 id） */
	private Long id;

	/** 记忆类型 */
	private String memType;

	/** 来源 */
	private String source;

	/** 一句话摘要 */
	private String title;

	/** 结论正文（全文） */
	private String contentMd;

	/** 逗号分隔标签 */
	private String tags;

	/** AC 笔记深链指针 */
	private String refUrl;

	/** 生命周期：1=active 2=archived 3=superseded（归档/被取代参与检索，靠此字段标记） */
	private Integer status;

	/** 取代本条的新记忆 id（status=3 时有值，调用方可顺链取最新版） */
	private Long supersededBy;

	/** 衰减权重 */
	private Integer weight;

	/** BM25 相关度得分（仅排序参考） */
	private float score;

	/** 最后命中时间（本次检索已刷新） */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date lastAccessedAt;
}
