package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_role")
public class RoleOnlyEntity extends BaseEntity {
	private static final long serialVersionUID = -1L;
	@TableId(type = IdType.AUTO)
	private Integer id;
	private String name;
	private String code;
	private Integer sort;
	private Integer status;
	private String remark;

	// 注：原 getCreateTime() / @JsonProperty 已迁移至 RoleVO
	// createTime 字段统一由 VO 层提供，Entity 不再承担序列化别名职责
}
