package com.youlxb2008.admin.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 档案实体
 * 对应数据库表 t_agent（docs/接口契约/data-model.md §2.1）
 * 注意：本表时间列名为 create_time/update_time（非管理端常见的 created_at），
 * 故不继承 BaseEntity，时间字段在本类显式声明。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_agent")
public class Agent {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** agent 名（alpha/bravo/gamma/cron），唯一 */
    private String name;

    /** 展示名（门户端显示） */
    private String displayName;

    /** 头像 URL（/uploads/... 相对路径；NULL=无头像，门户走色块兜底） */
    private String avatar;

    /** 能力描述 */
    private String description;

    /** 状态：1=active 0=suspended */
    private Integer status;

    /** 注册人（t_user.id） */
    private Long createdBy;

    /** 审批人（t_user.id） */
    private Long approvedBy;

    /** 审批时间 */
    private Date approvedAt;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除：0=未删除 1=已删除 */
    @TableLogic
    private Integer isDeleted;
}
