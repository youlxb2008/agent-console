package com.youlxb2008.admin.modules.agent.memory.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 门户记忆 VO（M7.1-M3，GET /api/portal/agents/{agentId}/memories/page 专用）
 * <p>
 * 门户白名单字段（任务书 M7.1-M3 §A1）：仅活跃（status=1）记忆的展示字段，
 * 不暴露 status/supersededBy/refUrl/lastAccessedAt/confirmedAt 等内部生命周期字段。
 * contentMd 全文直接返回（门户列表即读，无独立详情接口）。
 */
@Data
public class PortalMemoryVO {

	/** 对外引用键（数字 id） */
	private Long id;

	/** 记忆类型：fact/decision/preference/lesson/event */
	private String memType;

	/** 一句话摘要 */
	private String title;

	/** 结论正文（markdown 全文） */
	private String contentMd;

	/** 逗号分隔标签 */
	private String tags;

	/** 来源：dialogue/self/system */
	private String source;

	/** 衰减权重 */
	private Integer weight;

	/** 检索命中次数 */
	private Integer accessCount;

	/** 创建时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	/** 更新时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date updateTime;
}
