package com.youlxb2008.admin.modules.todo.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 私域 todolist 实体（M5.2，调研池迁入）
 * 对应数据库表 t_todo（docs/任务排期/M5.2-agent-todo.md）
 * <p>
 * 与 objective/ledger 不同：正文 content 为 MEDIUMTEXT 直接入库（真相源即本行，
 * 无文件系统），故无 body_path/存储 manager。
 * 软删模型：is_deleted=1（@TableLogic），全链路查询 is_deleted=0（fail-closed）。
 * 归属：agent_id 单值私域；越权（非本人）→ 403，不存在/已软删 → 404。
 * 排序契约：(due_date IS NULL) ASC, due_date ASC, priority DESC, id ASC
 * （M5.3：有截止日期在前按日期升序，无日期按优先级排后）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_todo")
public class Todo {

	private static final long serialVersionUID = 1L;

	/** 主键 ID，自增（纯内部，不外露） */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 对外引用键：d_ + 10 位 base62，随机生成终身不变 */
	private String todoId;

	/** 归属 agent（t_agent.id），每 agent 私域 todolist */
	private Long agentId;

	/** 标题（≤200 字符） */
	private String title;

	/** md 正文：来源/原因/产出要求（MEDIUMTEXT，直接入库） */
	private String content;

	/** 状态：0待办 1进行中 2已完成 3已放弃（对齐 objective 状态机） */
	private Integer status;

	/** 优先级 1-5（默认 3） */
	private Integer priority;

	/** 逗号分隔标签（对齐笔记 tags 惯例） */
	private String tags;

	/** 截止日期（可空；有值排前，过期标红） */
	private Date dueDate;

	/** 创建时间 */
	private Date createTime;

	/** 更新时间 */
	private Date updateTime;

	/** 逻辑删除：0=未删除 1=已删除（@TableLogic，全链路 fail-closed） */
	@TableLogic
	private Integer isDeleted;

	/** 所属 agent 展示名（非表字段：门户聚合回填用，来自 t_agent 联查） */
	@TableField(exist = false)
	private String agentName;
}
