package com.youlxb2008.admin.modules.system.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 通知已读记录实体
 * 对应表 t_notice_read，记录用户对某条通知的已读状态
 */
@Data
@TableName("t_notice_read")
public class NoticeRead {
	private static final long serialVersionUID = -1L;

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 通知ID */
	private Long noticeId;

	/** 用户ID */
	private Long userId;

	/** 阅读时间 */
	private Date readAt;
}
