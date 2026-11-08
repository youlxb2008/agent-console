package com.youlxb2008.admin.modules.system.vo;

import lombok.Data;

/**
 * 站点配置项视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：SiteOption
 */
@Data
public class SiteOptionVO {
	/** 主键ID，自增 */
	private Integer id;
	/** 配置项键名（如 site_title） */
	private String optionKey;
	/** 配置项键值 */
	private String optionValue;
}
