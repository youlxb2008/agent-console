package com.youlxb2008.admin.modules.notebook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.notebook.entity.AgentNotebook;

/**
 * agent↔笔记本 N:N 关联 Mapper（t_agent_notebook，v3.5 新增）
 * <p>
 * 基础 CRUD 由 {@link BaseMapper} 提供（物理删除模型，无 @TableLogic；
 * 解除关联 = 物理 DELETE）。纯关系行，无额外 JOIN 查询（关联查询见 {@link NotebookMapper}）。
 */
public interface AgentNotebookMapper extends BaseMapper<AgentNotebook> {
}