package com.youlxb2008.admin.modules.system.vo;

import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 角色视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：RoleOnlyEntity
 */
@Data
public class RoleVO {
	/** 主键ID */
	private Integer id;
	/** 角色名称 */
	private String name;
	/** 角色编码 */
	private String code;
	/** 排序值，越小越靠前 */
	private Integer sort;
	/** 状态：1-启用，0-禁用 */
	private Integer status;
	/** 备注 */
	private String remark;
	/** 创建时间（对应 BaseEntity.createdAt，统一前端字段名） */
	private Date createTime;
	/** 已分配的菜单/权限 ID 列表（供分配菜单弹窗回显） */
	private List<Integer> menuIds;
}
