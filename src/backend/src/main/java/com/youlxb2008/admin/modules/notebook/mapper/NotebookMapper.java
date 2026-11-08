package com.youlxb2008.admin.modules.notebook.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.notebook.entity.Notebook;
import com.youlxb2008.admin.modules.notebook.vo.NotebookAgentVO;

/**
 * 笔记本 Mapper（t_notebook，v3.5 新增）
 * <p>
 * 基础 CRUD 由 {@link BaseMapper} 提供（逻辑删除由 @TableLogic 自动处理）；
 * 跨表 JOIN（笔记本下 agent 关联列表）以 @Select 注解实现，不引入 XML。
 */
public interface NotebookMapper extends BaseMapper<Notebook> {

    /**
     * 笔记本→已关联 agent 列表（JOIN t_agent，含关联时间）
     * <p>
     * 关联弹窗/agent 管理页「所属笔记本」tab 数据源；过滤已逻辑删除的 agent。
     *
     * @param notebookId 笔记本 id
     * @return 已关联的 agent 列表（关联时间正序）
     */
    @Select("SELECT an.agent_id AS agentId, a.name AS name, a.display_name AS displayName, "
            + "an.create_time AS linkTime FROM t_agent_notebook an "
            + "JOIN t_agent a ON a.id = an.agent_id "
            + "WHERE an.notebook_id = #{notebookId} AND a.is_deleted = 0 "
            + "ORDER BY an.create_time ASC, an.id ASC")
    List<NotebookAgentVO> selectAgentsByNotebookId(@Param("notebookId") Long notebookId);
}