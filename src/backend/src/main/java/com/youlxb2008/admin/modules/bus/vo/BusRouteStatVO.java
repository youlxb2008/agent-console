package com.youlxb2008.admin.modules.bus.vo;

import lombok.Data;

/**
 * 方向近 24h 消息计数（bus-ui-v2，管理端 GET /system/bus/route-stats 行）
 */
@Data
public class BusRouteStatVO {

    /** 来源 agent id */
    private Long fromAgentId;

    /** 目标 agent id */
    private Long toAgentId;

    /** 近 24 小时消息条数 */
    private Long count24h;
}
