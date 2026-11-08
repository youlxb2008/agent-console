package com.youlxb2008.admin.modules.system.entity;

import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息通知实体类
 * 对应数据库表 t_notice，用于维护系统内的通知公告信息
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_notice")
public class Notice extends BaseEntity {

	private static final long serialVersionUID = -1L;

	/** 主键ID，自增 */
	@TableId(type = IdType.AUTO)
	private Long id;

	/** 通知标题 */
	private String title;

	/**
	 * 通知内容（支持纯文本或 HTML）
	 * 使用 TEXT 类型：HTML 内容长度不确定，VARCHAR(255) 容易超长
	 */
	private String content;

	/** 通知类型：1-系统通知，2-版本更新，3-维护公告 */
	private Integer type;

	/** 状态：1-已发布，0-草稿 */
	private Integer status;

	/** 发布人（用户名） */
	private String publisher;

	/** 备注 */
	private String remark;

	/** 接收者用户ID列表（非持久化，用于新增/修改时传递） */
	@TableField(exist = false)
	private List<Long> recipientUserIds;
}
