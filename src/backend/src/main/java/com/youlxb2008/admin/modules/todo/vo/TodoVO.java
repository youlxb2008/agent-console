package com.youlxb2008.admin.modules.todo.vo;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 待办 VO（M5.2，agent 域/门户共用）
 * <p>
 * 契约 VO 字段：todoId/title/content/status/priority/tags/dueDate/createTime/updateTime
 * （dueDate 为 M5.3 新增，@JsonFormat yyyy-MM-dd 对齐 objective targetDate）。
 * content 为 MEDIUMTEXT 直接入库，列表/详情共用同一 VO（真相源即索引行，无文件读取）。
 */
@Data
public class TodoVO {

	/** 对外引用键 d_xxx */
	private String todoId;

	/** 标题 */
	private String title;

	/** md 正文：来源/原因/产出要求 */
	private String content;

	/** 状态：0待办 1进行中 2已完成 3已放弃 */
	private Integer status;

	/** 优先级 1-5 */
	private Integer priority;

	/** 逗号分隔标签 */
	private String tags;

	/** 截止日期 yyyy-MM-dd（可空；有值排前，过期标红） */
	@JsonFormat(pattern = "yyyy-MM-dd")
	private Date dueDate;

	/** 创建时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date createTime;

	/** 最近更新时间 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private Date updateTime;
}
