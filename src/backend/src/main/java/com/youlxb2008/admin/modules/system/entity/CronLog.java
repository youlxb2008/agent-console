package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 定时任务执行日志实体类
 * 对应数据库表 t_cron_log，记录每次定时任务的执行结果
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_cron_log")
public class CronLog extends BaseEntity {

	private static final long serialVersionUID = -1L;

	/** 主键ID，自增 */
	@TableId(type = IdType.AUTO)
	private Integer id;

	/** 关联的定时任务ID */
	@TableField(value = "job_id")
	private Integer jobId;

	/** 执行状态：1-成功，0-失败 */
	private Integer status;

	/**
	 * 执行结果描述（成功信息或异常堆栈摘要）
	 * 使用 TEXT 类型：异常堆栈可能很长
	 */
	private String result;

	/** 执行耗时（毫秒） */
	private Long duration;
}
