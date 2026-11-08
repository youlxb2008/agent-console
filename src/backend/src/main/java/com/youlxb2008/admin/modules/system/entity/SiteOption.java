package com.youlxb2008.admin.modules.system.entity;

import java.io.Serializable;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站点配置项实体类
 * 对应数据库表 t_option，以 key-value 形式存储站点全局配置
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_option")
public class SiteOption implements Serializable {

	private static final long serialVersionUID = -1L;

	/** 主键ID，自增 */
	@TableId(type = IdType.AUTO)
	private Integer id;

	/** 配置项键名（如 site_title） */
	@TableField(value = "option_key")
	private String optionKey;

	/** 配置项键值 */
	@TableField(value = "option_value")
	private String optionValue;
}
