package com.youlxb2008.admin.modules.system.vo;

import java.util.Date;

import lombok.Data;

/**
 * 定时任务视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：CronJob
 */
@Data
public class CronJobVO {
	/** 主键ID，自增 */
	private Integer id;
	/** 任务名称 */
	private String name;
	/** cron 表达式（如 0 0/5 * * * ?） */
	private String cronExpr;
	/** 任务处理器全限定类名，需实现统一调度接口 */
	private String handlerClass;
	/** 任务执行参数（JSON 字符串） */
	private String params;
	/** 状态：1-启用，0-禁用 */
	private Integer status;
	/** 创建时间（对应 BaseEntity.createdAt，统一前端字段名） */
	private Date createTime;
}
