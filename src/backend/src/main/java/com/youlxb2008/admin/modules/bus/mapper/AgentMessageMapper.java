package com.youlxb2008.admin.modules.bus.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.bus.dto.BusTopicRange;
import com.youlxb2008.admin.modules.bus.entity.AgentMessage;
import org.apache.ibatis.annotations.Param;

/**
 * agent 消息总线 Mapper（M1）
 * <p>
 * 由 WebApplication 的 @MapperScan("com.youlxb2008.admin.**.mapper") 扫描，
 * 基于 MyBatis-Plus BaseMapper 提供基础 CRUD 能力。
 */
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    /**
     * 每个 topic 一行：首条（MIN id，方向基准）与末条（MAX id，超时判定对象）。
     * 只扫近 7 天活跃 topic（任务书①：防全表扫与陈年误报）；扫描器每轮一次，
     * topic 数量级小（活跃会话数），无分页需要。
     */
    @org.apache.ibatis.annotations.Select(
            "SELECT topic AS topic, MIN(id) AS firstId, MAX(id) AS lastId FROM t_agent_message "
                    + "WHERE create_time >= #{since} GROUP BY topic")
    List<BusTopicRange> selectTopicRanges(@Param("since") java.util.Date since);
}
