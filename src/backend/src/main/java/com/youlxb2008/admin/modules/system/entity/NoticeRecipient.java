package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 通知接收者实体
 * 对应表 t_notice_recipient，用于定向通知
 * 无记录=全员广播，有记录=仅指定用户可见
 */
@Data
@TableName("t_notice_recipient")
public class NoticeRecipient {
	private static final long serialVersionUID = -1L;

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 通知ID */
	private Long noticeId;

	/** 接收用户ID */
	private Long userId;
}
