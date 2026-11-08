package com.youlxb2008.admin.modules.notebook.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 独立笔记本实体（v3.5 新增，D1/D3/D4）
 * 对应数据库表 t_notebook（docs/版本/agent-v3.5_20260831 §1.1）
 * <p>
 * 笔记本是独立的内容容器（agent = 平台用户，与笔记本 N:N）。
 * <ul>
 *   <li>软删模型（D4）：is_deleted=1 + name 改写 {@code {name}__deleted_{id}} 释放原名</li>
 *   <li>门户/管理端查询一律过滤 is_deleted=0（fail-closed）</li>
 * </ul>
 * 时间列名为 create_time/update_time，显式声明（与 t_agent 同风格，不继承 BaseEntity）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_notebook")
public class Notebook {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 笔记本名（门户展示，未软删范围内唯一；软删时改写 {name}__deleted_{id}） */
    private String name;

    /** 简介 */
    private String description;

    /** 封面 URL（可选，列表页横幅封面，宽幅） */
    private String cover;

    /** logo URL（可选，笔记页左上角小标识，方图；v4.17 新增） */
    private String logo;

    /** hero URL（可选，笔记页空态中央展示图，宽幅；v4.17 新增） */
    private String hero;

    /** 状态：1=active 0=hidden */
    private Integer status;

    /** 创建人（t_user.id） */
    private Long createdBy;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除：0=未删除 1=已删除 */
    @TableLogic
    private Integer isDeleted;
}