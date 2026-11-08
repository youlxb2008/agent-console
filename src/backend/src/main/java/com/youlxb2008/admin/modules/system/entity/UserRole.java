package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_user_role")
public class UserRole  extends BaseEntity {
	private static final long serialVersionUID = 1369234132267034388L;
	
	@TableId(type = IdType.AUTO)
	private Integer id;
    private Integer roleId;
    private Integer userId;

}