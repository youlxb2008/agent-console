package com.youlxb2008.admin.modules.notebook.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * agent↔笔记本 N:N 关联实体（v3.5 新增，D2/D5）
 * 对应数据库表 t_agent_notebook（docs/版本/agent-v3.5_20260831 §1.2）
 * <p>
 * 纯关系行（与 t_agent_resource 同风格）：无 update_time/is_deleted，
 * 解除关联 = 物理 DELETE（D4）。唯一键 uk_agent_notebook(agent_id, notebook_id)。
 * 维护主体 = 管理员（D2：agent 注册可不归属，管理员后续补）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_agent_notebook")
public class AgentNotebook {

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的 agent（t_agent.id） */
    private Long agentId;

    /** 关联的笔记本（t_notebook.id） */
    private Long notebookId;

    /** 关联时间 */
    private Date createTime;

    /** 关联操作人（管理员用户名 / 系统） */
    private String createBy;
}