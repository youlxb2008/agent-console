package com.youlxb2008.admin.modules.system.vo;

import java.util.Date;

import lombok.Data;

/**
 * 定时任务执行日志视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：CronLog
 */
@Data
public class CronLogVO {
	/** 主键ID，自增 */
	private Integer id;
	/** 关联的定时任务ID */
	private Integer jobId;
	/** 执行状态：1-成功，0-失败 */
	private Integer status;
	/** 执行结果描述（成功信息或异常堆栈摘要） */
	private String result;
	/** 执行耗时（毫秒） */
	private Long duration;
	/** 创建时间（对应 BaseEntity.createdAt，统一前端字段名） */
	private Date createTime;
}
