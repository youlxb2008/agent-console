package com.youlxb2008.admin.modules.agent.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent 与资源 N:N 通用关联实体（v3 新增）
 * 对应数据库表 t_agent_resource（docs/版本/agent-v3_20260830 §2.1）
 * <p>
 * 通用关联表：resource_type 为扩展位，note=笔记（resource_id 填 nid），
 * 未来 chat/task/kb 直接挂新 type，本表结构不再变更。
 * 纯关系行：无 update_time/is_deleted，解除关联=物理 DELETE（与 t_note_meta 物理删模型一致）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_agent_resource")
public class AgentResource {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 agent（t_agent.id，逻辑外键，级联由代码维护） */
    private Long agentId;

    /** 资源类型：note=笔记；预留 chat/task/kb */
    private String resourceType;

    /** 资源外部键：note 填 nid（n_+10位base62） */
    private String resourceId;

    /** 关联时间 */
    private Date createTime;

    /** 关联操作人（管理员用户名/system-migration，agent 自建时为 agent 名） */
    private String createBy;

    /** resource_type 常量：笔记 */
    public static final String TYPE_NOTE = "note";

    /** resource_type 常量：目标（M4.1，每 agent 私域 todolist；创建时写作者关联行） */
    public static final String TYPE_OBJECTIVE = "objective";
}
