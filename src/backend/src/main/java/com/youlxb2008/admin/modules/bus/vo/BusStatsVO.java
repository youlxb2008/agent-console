package com.youlxb2008.admin.modules.bus.vo;

import lombok.Data;

/**
 * 消息总线统计 VO（管理端顶部统计卡 4 张，方案 §8）
 * <p>
 * 口径：今日进线 / 待投递(status=0 积压，全时段) / 投递失败(status=3 积压，全时段) /
 * 平均投递耗时（近 days 天 status=1 行 delivered_at-create_time 均值，秒）。
 */
@Data
public class BusStatsVO {

    /** 今日进线（create_time >= 今日零点） */
    private Long todayTotal;

    /** 待投递积压（status=0，全时段） */
    private Long pendingCount;

    /** 投递失败积压（status=3，全时段） */
    private Long failedCount;

    /** 平均投递耗时（秒，近 days 天已送达行；无数据时为 null） */
    private Double avgDeliverySeconds;

    /** 平均耗时的统计窗口（天） */
    private Integer days;
}
