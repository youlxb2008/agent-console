package com.youlxb2008.admin.modules.system.vo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.Data;

/**
 * 菜单/权限视图对象（Controller 出参）
 * 隐藏 Entity 内部字段，统一前端字段命名
 * 对应 Entity：UserPermission
 * <p>
 * 前端字段名别名说明：
 * - perms：对应 Entity 的 permissionKey
 * - path：对应 Entity 的 routePath
 * - sort：对应 Entity 的 sortOrder
 * - createTime：对应 BaseEntity 的 createdAt
 */
@Data
public class UserPermissionVO {
	/** 主键ID */
	private Integer id;
	/** 权限/菜单名称 */
	private String name;
	/** 权限标识（前端别名，对应 permissionKey），如 user:create */
	private String perms;
	/** 类型：1-目录 2-菜单 3-按钮 4-API */
	private Integer type;
	/** 父级ID */
	private Integer parentId;
	/** 层级深度（从0或1开始） */
	private Integer level;
	/** 前端路由路径（前端别名，对应 routePath），如 /user/list */
	private String path;
	/** 后端实体原始路由路径（保留以便编辑回显） */
	private String routePath;
	/** 前端组件路径，如 views/user/List.vue */
	private String component;
	/** 菜单图标（可选） */
	private String icon;
	/** 排序值（前端别名，对应 sortOrder），越小越靠前 */
	private Integer sort;
	/** 后端实体原始排序值（保留以便编辑回显） */
	private Integer sortOrder;
	/** 状态：1-启用，0-禁用 */
	private Integer status;
	/** 描述 */
	private String description;
	/** 创建时间（对应 BaseEntity.createdAt，统一前端字段名） */
	private Date createTime;
	/** 子权限列表（递归构建菜单树） */
	private List<UserPermissionVO> children = new ArrayList<>();
}
