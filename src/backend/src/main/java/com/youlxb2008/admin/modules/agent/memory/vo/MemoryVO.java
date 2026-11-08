package com.youlxb2008.admin.modules.agent.memory.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 记忆 VO（M7.1，agent 域列表/写入/更新共用）
 * <p>
 * 列表不回 contentMd 全文（TEXT 列，列表场景只需摘要与元数据）；
 * 详情走 /memories/{id}（MemoryDetailVO 含全文与 superseded 链）。
 */
@Data
public class MemoryVO {

	/** 对外引用键（数字 id） */
	private Long id;

	/** 归属 agent id */
	private Long agentId;

	/** 归属 agent 展示名（非表字段：管理端列表/统计回填用，display_name 优先回退 name） */
	private String agentName;

	/** 记忆类型：fact/decision/preference/lesson/event */
	private String memType;

	/** 来源：dialogue/self/system */
	private String source;

	/** 一句话摘要 */
	private String title;

	/** 逗号分隔标签 */
	private String tags;

	/** AC 笔记深链指针 */
	private String refUrl;

	/** 生命周期：1=active 2=archived 3=superseded */
	private Integer status;

	/** 取代本条的新记忆 id（status=3 时有值） */
	private Long supersededBy;

	/** 衰减权重 */
	private Integer weight;

	/** 权重保底线（0=不保底；M7.1-M3a） */
	private Integer weightFloor;

	/** 检索命中次数 */
	private Integer accessCount;

	/** 最后命中时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date lastAccessedAt;

	/** 最后确认时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date confirmedAt;

	/** 创建时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	/** 更新时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date updateTime;
}
