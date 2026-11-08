package com.youlxb2008.admin.modules.system.entity;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_user")
public class User extends BaseEntity {
    private static final long serialVersionUID = -1L;

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 昵称（前端字段名 nickname，DB 列名 name） */
    @TableField("name")
    private String nickname;
    private String username;
    /**
     * 密码（BCrypt 哈希）
     * JSON 访问策略：
     * - 序列化（出参）：不返回，避免密码泄露
     * - 反序列化（入参）：正常接收，新增/修改用户时需要明文传入，由 Service 层加密
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private Integer userType;
    private String email;
    private String phone;
    /** 状态：0-正常 1-禁用（前端字段名 status，DB 列名 enabled） */
    @TableField("enabled")
    private Integer status;
    private String avatar;
    private String remark;
    private Date lastLoginTime;
    /** 是否需要强制修改密码（首次登录） */
    @TableField("must_change_password")
    private Boolean mustChangePassword;

    /** 用户拥有的角色列表（非持久化，查询时填充） */
    @TableField(exist = false)
    private List<RoleOnlyEntity> roles;

    public enum UserType {
        ADMIN(0, "超级管理员"), //
        NORMAL(1, "普通用户"),//
        ;

        private final int value;
        private final String desc;

        UserType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static UserType of(int value) {
            for (UserType type : values()) {
                if (type.value == value)
                    return type;
            }
            throw new IllegalArgumentException("Invalid userType: " + value);
        }
    }

}
