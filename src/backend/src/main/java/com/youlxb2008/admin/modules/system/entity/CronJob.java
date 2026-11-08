package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 定时任务实体类
 * 对应数据库表 t_cron_job，描述一个定时调度任务的配置信息
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_cron_job")
public class CronJob extends BaseEntity {

	private static final long serialVersionUID = -1L;

	/** 主键ID，自增 */
	@TableId(type = IdType.AUTO)
	private Integer id;

	/** 任务名称 */
	private String name;

	/** cron 表达式（如 0 0/5 * * * ?） */
	@TableField(value = "cron_expr")
	private String cronExpr;

	/** 任务处理器全限定类名，需实现统一调度接口 */
	@TableField(value = "handler_class")
	private String handlerClass;

	/**
	 * 任务执行参数（JSON 字符串）
	 * 使用 TEXT 类型：复杂参数 JSON 容易超过 255 字符
	 */
	private String params;

	/** 状态：1-启用，0-禁用 */
	private Integer status;
}
