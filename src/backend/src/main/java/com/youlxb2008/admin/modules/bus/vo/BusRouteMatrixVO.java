package com.youlxb2008.admin.modules.bus.vo;

import lombok.Data;

/**
 * 投递路由矩阵行（bus-route-channel，管理端 GET /system/bus/routes）
 * <p>
 * 全部 agent 两两方向一行（含自投 from=to，bus-ui-v2 放开；按 fromAgentId、toAgentId 升序）；
 * 无配置行 channel=ws（存量默认，落库保存后按行生效）。
 */
@Data
public class BusRouteMatrixVO {

    /** 来源 agent id */
    private Long fromAgentId;

    /** 来源展示名（displayName 优先，回落 name） */
    private String fromAgentName;

    /** 目标 agent id */
    private Long toAgentId;

    /** 目标展示名（displayName 优先，回落 name） */
    private String toAgentName;

    /** 投递通道：ws / api（无配置行 = ws） */
    private String channel;
}
