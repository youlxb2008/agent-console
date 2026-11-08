package com.youlxb2008.admin.modules.bus.vo;

import java.util.List;

import lombok.Data;

/**
 * 门户消息流转统计 VO（M1 门户包；/bus 页顶部 4 统计胶囊数据源）
 * <p>
 * 口径：今日进线（create_time &gt;= 今日零点）/ 已投递（status=1，全时段）/
 * 待投（status=0，全时段）/ 失败（其余状态（2 已读/3 失败等）均按 failed 计，全时段）。
 */
@Data
public class PortalBusStatsVO {

    /** 今日进线（create_time >= 今日零点） */
    private Long todayTotal;

    /** 已投递（status=1，全时段） */
    private Long delivered;

    /** 待投（status=0，全时段） */
    private Long pending;

    /** 失败（status 不为 0/1 的其余状态合计，全时段） */
    private Long failed;

    /** 热门主题 top6（bus-ui-v2；全时段计数，count 降序） */
    private List<TopicCount> hotTopics;

    /** 全部主题计数（bus-ui-v2；筛选下拉数据源，count 降序、同数按 topic 升序） */
    private List<TopicCount> topics;

    /** 主题计数项（bus-ui-v2） */
    @Data
    public static class TopicCount {

        /** 场景标签 */
        private String topic;

        /** 消息条数（全时段） */
        private Long count;
    }
}
