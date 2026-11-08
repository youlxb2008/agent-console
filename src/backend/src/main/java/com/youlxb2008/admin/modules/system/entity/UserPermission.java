package com.youlxb2008.admin.modules.system.entity;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_permission")
public class UserPermission extends BaseEntity implements Serializable {

	private static final long serialVersionUID = -1L;

	@TableId(type = IdType.AUTO)
	private Integer id;

	/**
	 * 权限/菜单名称
	 */
	private String name;

	/**
	 * 权限标识符，如 user:create（全局唯一）
	 */
	@TableField(value = "permission_key")
	private String permissionKey;

	/**
	 * 类型：1-目录 2-菜单 3-按钮 4-API
	 */
	private Integer type;

	/**
	 * 父级ID
	 */
	private Integer parentId;

	/**
	 * 层级深度（从0或1开始）
	 */
	private Integer level;

	/**
	 * 前端路由路径，如 /user/list
	 */
	@TableField(value = "route_path")
	private String routePath;

	/**
	 * 前端组件路径，如 views/user/List.vue
	 */
	private String component;

	/**
	 * 菜单图标（可选）
	 */
	private String icon;

	/**
	 * 排序值，越小越靠前
	 */
	@TableField(value = "sort_order")
	private Integer sortOrder;

	/**
	 * 状态：1-启用，0-禁用
	 */
	private Integer status;

	/**
	 * 描述
	 */
	private String description;

	@TableField(exist = false)
	private List<UserPermission> children = new ArrayList<>();

	// 注：原 getPerms() / getPath() / getSort() / getCreateTime() 及 @JsonProperty
	// 已迁移至 UserPermissionVO，Entity 不再承担序列化别名职责
}
