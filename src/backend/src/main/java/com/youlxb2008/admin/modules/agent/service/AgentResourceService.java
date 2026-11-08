package com.youlxb2008.admin.modules.agent.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.AgentResource;
import com.youlxb2008.admin.modules.agent.mapper.AgentResourceMapper;
import com.youlxb2008.admin.modules.agent.vo.AgentSimpleVO;

/**
 * agent-资源通用关联 Service（v3 新增）
 * <p>
 * N:N 关联表的唯一写入口：agent 自建笔记（创建者关联）、管理端双向关联维护、
 * 删笔记级联清关联，全部经本类完成，保证幂等与差集可审计。
 * 纯关系行物理删除模型：解除关联=DELETE，无 @TableLogic。
 */
@Service
public class AgentResourceService extends ServiceImpl<AgentResourceMapper, AgentResource> {

    /** agent 域内部 Service→Service：全量覆盖时过滤不存在的 agentId */
    @Autowired
    private AgentService agentService;

    /**
     * 全量覆盖结果（差集，供审计 note.link / note.unlink）
     *
     * @param added   本次新增关联的 agentId 列表
     * @param removed 本次移除关联的 agentId 列表
     */
    public record ReplaceResult(List<Long> added, List<Long> removed) {
    }

    /**
     * 单条关联（幂等：唯一键冲突即已存在，直接忽略返回）
     * <p>
     * 场景：agent 新建笔记时写创建者关联；管理端逐条补挂。
     *
     * @param agentId      agent id
     * @param resourceType 资源类型（AgentResource.TYPE_NOTE）
     * @param resourceId   资源外部键（nid）
     * @param createBy     操作人（agent 名 / 管理员用户名）
     */
    public void link(Long agentId, String resourceType, String resourceId, String createBy) {
        // 幂等守卫：先查后插（并发窗口依赖 uk_agent_resource 唯一键兜底，冲突时忽略）
        boolean exists = count(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getAgentId, agentId)
                .eq(AgentResource::getResourceType, resourceType)
                .eq(AgentResource::getResourceId, resourceId)) > 0;
        if (exists) {
            return;
        }
        AgentResource row = new AgentResource();
        row.setAgentId(agentId);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setCreateBy(createBy);
        row.setCreateTime(new Date());
        save(row);
    }

    /**
     * 单条解除（物理 DELETE；资源与其他 agent 的关联不受影响）
     *
     * @param agentId      agent id
     * @param resourceType 资源类型
     * @param resourceId   资源外部键
     * @return 是否实际删除了行（false=本就未关联）
     */
    public boolean unlink(Long agentId, String resourceType, String resourceId) {
        return remove(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getAgentId, agentId)
                .eq(AgentResource::getResourceType, resourceType)
                .eq(AgentResource::getResourceId, resourceId));
    }

    /**
     * 笔记关联全量覆盖（事务：删旧插新，去重）
     * <p>
     * 管理端「关联 Agent」弹窗保存语义：目标集合即最终态。
     * 不存在的 agentId 由调用方（Controller）先行过滤，本方法只做纯集合覆盖。
     *
     * @param nid       笔记 nid（resource_id）
     * @param agentIds  目标 agent 集合（最终态，允许为空=清空全部关联）
     * @param createBy  操作人（管理员用户名）
     * @return 差集（新增/移除的 agentId），供审计 note.link / note.unlink
     */
    @Transactional(rollbackFor = Exception.class)
    public ReplaceResult replaceAgents(String nid, List<Long> agentIds, String createBy) {
        // 0. 过滤不存在的 agentId（逻辑删除/误传 id 直接忽略，设计 §5.2 PUT 语义）
        List<Long> validIds = agentIds == null ? List.of() : agentIds.stream().distinct().toList();
        List<Long> existingIds = validIds.isEmpty() ? List.of()
                : agentService.listByIds(validIds).stream().map(Agent::getId).toList();

        // 1. 目标集合去重 + 空集兜底（null 视为清空，与前端全不勾语义一致）
        Set<Long> target = new HashSet<>(existingIds);

        // 2. 读现有关联，计算差集（old-target=移除，target-old=新增）
        List<AgentResource> oldRows = list(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getResourceType, AgentResource.TYPE_NOTE)
                .eq(AgentResource::getResourceId, nid));
        Set<Long> oldIds = new HashSet<>();
        for (AgentResource row : oldRows) {
            oldIds.add(row.getAgentId());
        }
        List<Long> removed = new ArrayList<>();
        for (AgentResource row : oldRows) {
            if (!target.contains(row.getAgentId())) {
                removed.add(row.getAgentId());
            }
        }
        List<Long> added = new ArrayList<>();
        for (Long id : target) {
            if (!oldIds.contains(id)) {
                added.add(id);
            }
        }

        // 3. 全删全插（比逐条 diff 简单可靠；事务保证原子性）
        if (!oldRows.isEmpty()) {
            remove(Wrappers.<AgentResource>lambdaQuery()
                    .eq(AgentResource::getResourceType, AgentResource.TYPE_NOTE)
                    .eq(AgentResource::getResourceId, nid));
        }
        for (Long agentId : target) {
            AgentResource row = new AgentResource();
            row.setAgentId(agentId);
            row.setResourceType(AgentResource.TYPE_NOTE);
            row.setResourceId(nid);
            row.setCreateBy(createBy);
            row.setCreateTime(new Date());
            save(row);
        }
        return new ReplaceResult(added, removed);
    }

    /**
     * 资源→已关联 agent 档案列表（JOIN t_agent，过滤逻辑删除）
     * <p>
     * 用于：关联弹窗回显 / 门户 contributors / 管理端列表贡献者列。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源外部键（nid）
     * @return 已关联 agent 列表（关联时间正序，创建者在首）
     */
    public List<Agent> listAgentsByResource(String resourceType, String resourceId) {
        return baseMapper.selectAgentsByResource(resourceType, resourceId);
    }

    /**
     * 笔记贡献者列表（AgentSimpleVO 组装，关联弹窗回显 / 管理端列表 / 门户共用）
     * <p>
     * 展示名规则：display_name 优先，无则回退 name（=笔记本名）。
     *
     * @param nid 笔记 nid
     * @return 贡献者列表（关联时间正序，创建者在首）
     */
    public List<AgentSimpleVO> listNoteContributors(String nid) {
        return listAgentsByResource(AgentResource.TYPE_NOTE, nid).stream()
                .map(a -> new AgentSimpleVO(a.getId(), a.getName(),
                        a.getDisplayName() != null ? a.getDisplayName() : a.getName()))
                .toList();
    }

    /**
     * agent→资源键列表（tree/检索过滤的前置查询）
     * <p>
     * 查询族模式：先取该 agent 关联的全部 nid，再 `in` 过滤 t_note_meta，
     * 避免在 note 域写反向 JOIN SQL。
     *
     * @param agentId      agent id
     * @param resourceType 资源类型
     * @return 资源键（nid）列表；无关联返回空列表
     */
    public List<String> listResourceIdsByAgent(Long agentId, String resourceType) {
        return list(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getAgentId, agentId)
                .eq(AgentResource::getResourceType, resourceType))
                .stream().map(AgentResource::getResourceId).toList();
    }

    /**
     * 多 agent→笔记键并集（门户查询族的批量版）
     * <p>
     * 语义：agentIds=null 不过滤（全部笔记可见域由调用方决定）；空列表=无匹配 agent → 空结果；
     * 非空=IN 过滤。结果去重（同一笔记被多个 agent 关联时只留一份）。
     *
     * @param agentIds agent id 列表（null=不过滤；空=空结果）
     * @return 去重后的 nid 集合
     */
    public List<String> listNoteIdsByAgents(List<Long> agentIds) {
        if (agentIds != null && agentIds.isEmpty()) {
            return new ArrayList<>();
        }
        // DISTINCT 去重：note 域 IN 过滤与聚合统计均按笔记唯一计
        return list(Wrappers.<AgentResource>lambdaQuery()
                .in(agentIds != null, AgentResource::getAgentId, agentIds)
                .eq(AgentResource::getResourceType, AgentResource.TYPE_NOTE)
                .select(AgentResource::getResourceId))
                .stream().map(AgentResource::getResourceId).distinct().toList();
    }

    /**
     * 是否已关联（协作写/删的权限校验：关联即可协作写）
     *
     * @param agentId      agent id
     * @param resourceType 资源类型
     * @param resourceId   资源外部键（nid）
     * @return true=存在关联行
     */
    public boolean hasLink(Long agentId, String resourceType, String resourceId) {
        return count(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getAgentId, agentId)
                .eq(AgentResource::getResourceType, resourceType)
                .eq(AgentResource::getResourceId, resourceId)) > 0;
    }

    /**
     * 删除资源时级联清空全部关联行（物理 DELETE）
     * <p>
     * 删笔记三步（文件→索引→关联）的最后一步。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源外部键（nid）
     * @return 删除的行数
     */
    public int deleteByResource(String resourceType, String resourceId) {
        return baseMapper.delete(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getResourceType, resourceType)
                .eq(AgentResource::getResourceId, resourceId));
    }

    /**
     * agent 关联笔记数（管理端 agent 列表 noteCount 列 / 门户卡片笔记数）
     *
     * @param agentId agent id
     * @return 关联的 note 行数
     */
    public long countByAgent(Long agentId) {
        return count(Wrappers.<AgentResource>lambdaQuery()
                .eq(AgentResource::getAgentId, agentId)
                .eq(AgentResource::getResourceType, AgentResource.TYPE_NOTE));
    }

    /**
     * agent 关联笔记的最新更新时间（门户笔记本卡片"最近更新"）
     *
     * @param agentId agent id
     * @return MAX(update_time)；无关联返回 null
     */
    public Date maxUpdateTimeByAgent(Long agentId) {
        return baseMapper.selectMaxNoteUpdateTimeByAgent(agentId);
    }

}
