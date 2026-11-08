package com.youlxb2008.admin.modules.bus.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.youlxb2008.admin.exceptions.UnprocessableEntityException;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.mapper.AgentMapper;
import com.youlxb2008.admin.modules.bus.dto.BusRouteSaveReq;
import com.youlxb2008.admin.modules.bus.entity.BusRoute;
import com.youlxb2008.admin.modules.bus.mapper.BusRouteMapper;
import com.youlxb2008.admin.modules.bus.vo.BusRouteMatrixVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 投递通道方向级路由服务（bus-route-channel）
 * <p>
 * - 投递判定：{@link #resolveChannel} 无配置行 = ws（向后兼容，存量行为不变）；
 *   每次投递前现查（不缓存），管理端保存即时生效（下一条消息按新通道投递）。
 * - 管理矩阵：全部 agent 两两方向一行（含自投 from=to，bus-ui-v2 放开），
 *   无行显示 ws 默认。
 * - 保存：upsert（切 api 而目标行已存在则更新 channel）；channel 仅 ws/api；
 *   自投方向可保存（bus-ui-v2，自投消息不参与熔断监控，BusFuseService 豁免不变）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusChannelRouteService {

    private final BusRouteMapper routeMapper;
    private final AgentMapper agentMapper;

    /**
     * 投递通道判定（dispatcher 投递前调用；现查库不缓存，保存即时生效）。
     * 入参空值 / 配置行 channel 非法值均防御性按 ws。
     */
    public String resolveChannel(Long fromAgentId, Long toAgentId) {
        if (fromAgentId == null || toAgentId == null) {
            return BusRoute.CHANNEL_WS;
        }
        BusRoute row = routeMapper.selectOne(Wrappers.<BusRoute>lambdaQuery()
                .eq(BusRoute::getFromAgentId, fromAgentId)
                .eq(BusRoute::getToAgentId, toAgentId)
                .last("LIMIT 1"));
        if (row == null) {
            return BusRoute.CHANNEL_WS;
        }
        return normalize(row.getChannel());
    }

    /**
     * 方向矩阵：全部 agent 两两方向一行（含自投 from=to，bus-ui-v2 放开；
     * 按 fromAgentId、toAgentId 升序），无配置行 channel=ws。
     */
    public List<BusRouteMatrixVO> matrix() {
        List<Agent> agents = agentMapper.selectList(Wrappers.<Agent>lambdaQuery().orderByAsc(Agent::getId));
        Map<Long, String> names = new HashMap<>();
        for (Agent a : agents) {
            names.put(a.getId(), a.getDisplayName() != null && !a.getDisplayName().isBlank()
                    ? a.getDisplayName() : a.getName());
        }
        Map<String, String> configured = new HashMap<>();
        for (BusRoute row : routeMapper.selectList(null)) {
            configured.put(row.getFromAgentId() + ":" + row.getToAgentId(), row.getChannel());
        }
        List<BusRouteMatrixVO> out = new ArrayList<>();
        for (Agent from : agents) {
            for (Agent to : agents) {
                BusRouteMatrixVO vo = new BusRouteMatrixVO();
                vo.setFromAgentId(from.getId());
                vo.setFromAgentName(names.get(from.getId()));
                vo.setToAgentId(to.getId());
                vo.setToAgentName(names.get(to.getId()));
                vo.setChannel(normalize(configured.get(from.getId() + ":" + to.getId())));
                out.add(vo);
            }
        }
        out.sort((x, y) -> {
            int c = Long.compare(x.getFromAgentId(), y.getFromAgentId());
            return c != 0 ? c : Long.compare(x.getToAgentId(), y.getToAgentId());
        });
        return out;
    }

    /**
     * 保存单方向路由（upsert；channel 仅 ws/api；自投方向允许，bus-ui-v2；保存即时生效）
     */
    @Transactional
    public void save(long fromAgentId, long toAgentId, String channel) {
        validate(fromAgentId, toAgentId, channel);
        BusRoute row = routeMapper.selectOne(Wrappers.<BusRoute>lambdaQuery()
                .eq(BusRoute::getFromAgentId, fromAgentId)
                .eq(BusRoute::getToAgentId, toAgentId)
                .last("LIMIT 1"));
        if (row == null) {
            row = new BusRoute();
            row.setFromAgentId(fromAgentId);
            row.setToAgentId(toAgentId);
            row.setChannel(channel);
            row.setCreateTime(new Date());
            row.setUpdateTime(new Date());
            routeMapper.insert(row);
        } else {
            row.setChannel(channel);
            row.setUpdateTime(new Date());
            routeMapper.updateById(row);
        }
        log.info("[agent-bus] 投递路由已保存: {} -> {} channel={}", fromAgentId, toAgentId, channel);
    }

    /**
     * 批量保存（整表提交，逐行校验 + upsert；任一行非法整体抛错不落库）
     *
     * @return 保存条数
     */
    @Transactional
    public int saveBatch(List<BusRouteSaveReq> items) {
        if (items == null || items.isEmpty()) {
            throw new UnprocessableEntityException("批量保存内容为空");
        }
        for (BusRouteSaveReq item : items) {
            if (item == null || item.getFromAgentId() == null || item.getToAgentId() == null) {
                throw new UnprocessableEntityException("批量保存存在缺 fromAgentId/toAgentId 的行");
            }
            validate(item.getFromAgentId(), item.getToAgentId(), item.getChannel());
        }
        for (BusRouteSaveReq item : items) {
            save(item.getFromAgentId(), item.getToAgentId(), item.getChannel());
        }
        return items.size();
    }

    private void validate(long fromAgentId, long toAgentId, String channel) {
        if (channel == null || (!BusRoute.CHANNEL_WS.equals(channel) && !BusRoute.CHANNEL_API.equals(channel))) {
            throw new UnprocessableEntityException("channel 仅支持 ws/api: " + channel);
        }
        // bus-ui-v2：from=to（自投）允许保存（默认 ws；自投消息不参与熔断监控）
    }

    /**
     * 删除某发件方的全部路由行（管理端「恢复默认」，bus-ui-v2）：
     * 无配置行 = ws，删行即回落默认投递通道。
     *
     * @return 删除行数
     */
    @Transactional
    public int clearByFrom(long fromAgentId) {
        int deleted = routeMapper.delete(Wrappers.<BusRoute>lambdaQuery()
                .eq(BusRoute::getFromAgentId, fromAgentId));
        if (deleted > 0) {
            log.info("[agent-bus] 投递路由已清空（回落默认 ws）: from={} rows={}", fromAgentId, deleted);
        }
        return deleted;
    }

    /**
     * 方向是否存在显式配置行（v4.37 总线调试：区分「有行还原 channel」与「无行删行回落默认 ws」）
     */
    public boolean hasExplicitRoute(long fromAgentId, long toAgentId) {
        Long count = routeMapper.selectCount(Wrappers.<BusRoute>lambdaQuery()
                .eq(BusRoute::getFromAgentId, fromAgentId)
                .eq(BusRoute::getToAgentId, toAgentId));
        return count != null && count > 0;
    }

    /**
     * 删除单方向配置行（v4.37 总线调试还原：原无配置行 → 删掉临时行回落默认 ws）
     *
     * @return 删除行数
     */
    @Transactional
    public int deleteRoute(long fromAgentId, long toAgentId) {
        int deleted = routeMapper.delete(Wrappers.<BusRoute>lambdaQuery()
                .eq(BusRoute::getFromAgentId, fromAgentId)
                .eq(BusRoute::getToAgentId, toAgentId));
        if (deleted > 0) {
            log.info("[agent-bus] 投递路由行已删除（回落默认 ws）: {} -> {}", fromAgentId, toAgentId);
        }
        return deleted;
    }

    /** channel 防御性归一：非 api 一律按 ws（存量语义） */
    private String normalize(String channel) {
        return BusRoute.CHANNEL_API.equals(channel) ? BusRoute.CHANNEL_API : BusRoute.CHANNEL_WS;
    }
}
