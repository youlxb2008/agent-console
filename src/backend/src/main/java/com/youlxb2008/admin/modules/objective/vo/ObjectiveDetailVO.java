package com.youlxb2008.admin.modules.objective.vo;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.youlxb2008.admin.modules.objective.manager.ObjectiveStorageManager.SubItem;

import lombok.Data;

/**
 * 目标详情 VO（M4.1，agent 域/管理端/门户共用）
 * <p>
 * 索引字段 + 详情 md 全文（descMd）+ checkbox 子项解析结果（subItems）。
 * md 文件缺失（孤儿索引行）→ 404（ObjectiveService.getDetail/readDetail 兜底）。
 */
@Data
public class ObjectiveDetailVO {

	/** 对外引用键 o_xxx */
	private String oid;

	/** 归属 agent id（门户/管理端回填；agent 域不返回） */
	private Long agentId;

	/** 归属 agent 展示名（管理端回填） */
	private String agentName;

	/** 目标标题 */
	private String title;

	/** 状态：0未开始 1进行中 2已完成 3已放弃 */
	private Integer status;

	/** 周期 */
	private String period;

	/** 截止日期 yyyy-MM-dd */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date targetDate;

	/** 进度 0-100 */
	private BigDecimal progress;

	/** 详情 md 全文（`## 描述` + `## 子项` + `## 反思`） */
	private String contentMd;

	/** checkbox 子项解析结果（- [x]/- [ ]） */
	private List<SubItem> subItems;

	/** 最近更新时间 */
	private Date updateTime;
}
