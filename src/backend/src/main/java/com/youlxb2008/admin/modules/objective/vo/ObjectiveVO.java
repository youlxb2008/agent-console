package com.youlxb2008.admin.modules.objective.vo;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 目标列表项 VO（M4.1，agent 域/管理端/门户共用）
 * <p>
 * 只读索引字段，不读文件；progress/status/period/targetDate 均在索引列上。
 * agent 域响应不带 agentId/agentName（私域）；管理端/门户按场景回填。
 */
@Data
public class ObjectiveVO {

	/** 对外引用键 o_xxx */
	private String oid;

	/** 归属 agent id（管理端/门户回填；agent 域不返回） */
	private Long agentId;

	/** 归属 agent 展示名（管理端回填；display_name 优先回退 name） */
	private String agentName;

	/** 目标标题 */
	private String title;

	/** 状态：0未开始 1进行中 2已完成 3已放弃 */
	private Integer status;

	/** 周期：2026-Q4 / 2026 / 自由文本 */
	private String period;

	/** 截止日期 yyyy-MM-dd */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date targetDate;

	/** 进度 0-100（详情 md 子项 checkbox 解析结果） */
	private BigDecimal progress;

	/** 最近更新时间 */
	private Date updateTime;
}
