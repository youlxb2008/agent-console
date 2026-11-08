package com.youlxb2008.admin.modules.objective.entity;

import java.math.BigDecimal;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 目标索引实体（M4.1，agent 私域 todolist）
 * 对应数据库表 t_objective（docs/版本/agent-v4_20260831 §1.1）
 * <p>
 * 真相源在 OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md 文件系统：
 * 详情正文（`## 描述` + `## 子项` checkbox + `## 反思`）由 agent 直接维护，
 * 本表只存索引（title/status/period/target_date/progress/body_path）。
 * progress = 详情 md 子项 checkbox 解析 done/total*100，写接口时刷新。
 * 软删模型：is_deleted=1（@TableLogic），md 文件保留；全链路查询 is_deleted=0（fail-closed）。
 * 归属：agent_id 单值私域（每 agent 一个 todolist）；创建时另写 t_agent_resource(type=objective)
 * 关联行（与笔记 D6 同范式，未来多 agent 协作/关联需求扩展位）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_objective")
public class Objective {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（纯内部，不外露） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 对外引用键：o_ + 10 位 base62，随机生成终身不变 */
	private String oid;

	/** 归属 agent（t_agent.id），每 agent 私域 todolist */
	private Long agentId;

	/** 目标标题（todolist 条目） */
	private String title;

	/** 状态：0未开始 1进行中 2已完成 3已放弃 */
	private Integer status;

	/** 周期：2026-Q4 / 2026 / 自由文本 */
	private String period;

	/** 截止日期 */
	private Date targetDate;

	/** 进度 0-100（详情 md 子项 checkbox 解析，写接口时刷新） */
	private BigDecimal progress;

	/** 详情 md 路径 OBJECTIVES_ROOT/agent-{id}/objective-{oid}.md */
	private String bodyPath;

	/** 创建时间 */
	private Date createTime;

	/** 更新时间 */
	private Date updateTime;

	/** 逻辑删除：0=未删除 1=已删除（@TableLogic，全链路 fail-closed） */
	@TableLogic
	private Integer isDeleted;

	/** 所属 agent 展示名（非表字段：管理端/门户回填用，来自 t_agent 联查） */
	@TableField(exist = false)
	private String agentName;
}
