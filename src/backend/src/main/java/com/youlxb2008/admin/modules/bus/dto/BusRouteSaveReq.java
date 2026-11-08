package com.youlxb2008.admin.modules.bus.dto;

import lombok.Data;

/**
 * 投递路由保存请求（bus-route-channel，管理端 PUT /system/bus/routes）
 * <p>
 * 校验在服务层（BusChannelRouteService）：channel 仅 ws/api；from != to；
 * from/to 必填——统一 422 语义，不走 bean 校验注解（BindException 会兜成 200 envelope）。
 */
@Data
public class BusRouteSaveReq {

    /** 来源 agent id */
    private Long fromAgentId;

    /** 目标 agent id */
    private Long toAgentId;

    /** 投递通道：ws / api */
    private String channel;
}
