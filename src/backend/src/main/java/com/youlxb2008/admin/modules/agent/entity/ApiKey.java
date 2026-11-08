package com.youlxb2008.admin.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent API Key 实体
 * 对应数据库表 t_api_key（docs/接口契约/data-model.md §2.2）
 * 注意：v4.1 起明文 key 同步落库（keyPlain），供管理端历史 key 随时查看/复制；
 * 旧数据该列为 NULL（未存明文），前端做不可复制兜底；
 * scopes 为 JSON 列，Java 侧以 String 承载（与 管理端 AuditLog.params 存 JSON 风格一致）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_api_key")
public class ApiKey {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 t_agent.id */
    private Long agentId;

    /** SHA-256(明文 key)，用于鉴权比对 */
    private String keyHash;

    /** 明文前缀（如 ac_demo_ab12），识别用 */
    private String keyPrefix;

    /** 明文 key（v4.1 起落库；管理端列表展示/复制用；NULL=旧 key 未存明文） */
    private String keyPlain;

    /** 权限列表 JSON，如 ["notes:write","audit:read"] */
    private String scopes;

    /** 状态：1=active 0=revoked */
    private Integer status;

    /** 过期时间，NULL=永久 */
    private Date expiresAt;

    /** 最后使用时间 */
    private Date lastUsedAt;

    /** 作废人（t_user.id） */
    private Long revokedBy;

    /** 作废时间 */
    private Date revokedAt;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除：0=未删除 1=已删除 */
    @TableLogic
    private Integer isDeleted;
}
