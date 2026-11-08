package com.youlxb2008.admin.modules.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.youlxb2008.admin.utils.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 审计日志实体
 * 记录"谁、什么时候、对什么资源、做了什么操作"
 * 用于系统操作审计、安全追溯、问题排查
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_sys_audit_log")
public class AuditLog extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户名（冗余字段，便于按用户名查询） */
    private String username;

    /** 操作人用户 ID */
    private Long userId;

    /** 请求方法（GET/POST/PUT/DELETE） */
    private String method;

    /** 请求路径（URI） */
    private String path;

    /** 操作描述（从 Controller 方法的 @Operation 注解 summary 读取） */
    private String description;

    /**
     * 请求参数（JSON 字符串，已过滤敏感字段）
     * 使用 TEXT 类型：JSON 入参经常超过 255 字符（如新增用户请求体）
     */
    private String params;

    /** 响应状态码（保留字段，目前未填充） */
    private Integer status;

    /** 接口耗时（毫秒） */
    private Long duration;

    /** 客户端 IP 地址（支持反向代理透传） */
    private String ipAddress;

    /** 是否成功：1=成功，0=失败（抛异常视为失败） */
    private Integer success;
}
