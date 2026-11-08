package com.youlxb2008.admin.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 域审计日志实体
 * 对应数据库表 t_audit_log（docs/接口契约/data-model.md §2.3）
 * 硬规则：append-only，禁止 UPDATE/DELETE（代码层约束，本类无逻辑删除字段）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_audit_log")
public class AuditLog {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作主体（agent 名/用户名） */
    private String principal;

    /** 操作类型：key.create / key.revoke / note.write / note.delete / note.link / note.unlink / agent.create / auth.me / audit.list / portal.read / objective.read / objective.create / objective.update / objective.delete ...（v3 新增 note.link / note.unlink 关联维护；M4.1 新增 objective.* 目标模块） */
    private String action;

    /** 操作对象（note id / key id ...） */
    private String resource;

    /** 请求摘要 JSON（method/path/参数，脱敏） */
    private String request;

    /** 结果：success / denied / error */
    private String result;

    /** 客户端 IP */
    private String clientIp;

    /** 创建时间 */
    private Date createTime;
}
