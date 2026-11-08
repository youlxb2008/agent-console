package com.youlxb2008.admin.modules.system.vo;

import java.util.Date;

import lombok.Data;

/**
 * 消息通知视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：Notice
 */
@Data
public class NoticeVO {
	/** 主键ID，自增 */
	private Long id;
	/** 通知标题 */
	private String title;
	/** 通知内容（支持纯文本或 HTML） */
	private String content;
	/** 通知类型：1-系统通知，2-版本更新，3-维护公告 */
	private Integer type;
	/** 状态：1-已发布，0-草稿 */
	private Integer status;
	/** 发布人（用户名） */
	private String publisher;
	/** 备注 */
	private String remark;
	/** 创建时间（对应 BaseEntity.createdAt，统一前端字段名） */
	private Date createTime;
	/** 是否已读（用户侧接口专用） */
	private Boolean isRead;
}
