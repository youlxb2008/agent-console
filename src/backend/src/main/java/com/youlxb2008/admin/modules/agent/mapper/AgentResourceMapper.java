package com.youlxb2008.admin.modules.agent.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;

/**
 * agent-资源关联 Mapper（t_agent_resource）
 * <p>
 * 基础 CRUD 由 {@link BaseMapper} 提供（物理删除模型，无 @TableLogic）；
 * 跨表 JOIN 查询（资源→agent 列表、agent 维度聚合）以 @Select 注解实现，
 * 与 BaseMapper 风格保持一致，不引入 XML。
 */
public interface AgentResourceMapper extends BaseMapper<AgentResource> {

    /**
     * 资源→已关联 agent 档案列表（JOIN t_agent）
     * <p>
     * 用于：笔记关联弹窗回显、门户 contributors、管理端列表贡献者列。
     * 按关联时间正序保证回显顺序稳定（创建者通常最早关联）。
     *
     * @param resourceType 资源类型（note）
     * @param resourceId   资源外部键（nid）
     * @return 已关联的 agent 列表（仅取档案字段，不含已逻辑删除的 agent）
     */
    @Select("SELECT a.* FROM t_agent_resource r JOIN t_agent a ON a.id = r.agent_id "
            + "WHERE r.resource_type = #{resourceType} AND r.resource_id = #{resourceId} "
            + "AND a.is_deleted = 0 ORDER BY r.create_time ASC, r.id ASC")
    List<Agent> selectAgentsByResource(@Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId);

    /**
     * agent 维度聚合：该 agent 全部关联笔记的最新更新时间（门户笔记本卡片"最近更新"）
     * <p>
     * t_agent_resource JOIN t_note_meta 取 MAX(update_time)；无任何关联时返回 null。
     *
     * @param agentId agent id
     * @return 最新 update_time；无关联返回 null
     */
    @Select("SELECT MAX(n.update_time) FROM t_agent_resource r JOIN t_note_meta n ON n.nid = r.resource_id "
            + "WHERE r.agent_id = #{agentId} AND r.resource_type = 'note'")
    java.util.Date selectMaxNoteUpdateTimeByAgent(@Param("agentId") Long agentId);

}
