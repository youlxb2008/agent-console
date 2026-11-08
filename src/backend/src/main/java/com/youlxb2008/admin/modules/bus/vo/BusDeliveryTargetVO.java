package com.youlxb2008.admin.modules.bus.vo;

import lombok.Data;

/**
 * 投递通道可用性（bus-ui-v2，管理端 GET /system/bus/delivery-targets 行）
 * <p>
 * 解析 env 配置（启动期静态）：wsConfigured = BUS_TARGET_* 含该目标；
 * apiConfigured = BUS_API_TARGET_* 含该目标。两项均 false = 「无实例」（不参与投递）。
 * apiBaseUrl 仅管理端只读展示（BUS_API_TARGET 的 baseUrl 段；apiKey 绝不出域）。
 */
@Data
public class BusDeliveryTargetVO {

    /** agent id */
    private Long agentId;

    /** ws 通道是否已配置（BUS_TARGET_* 含该目标） */
    private boolean wsConfigured;

    /** api 通道是否已配置（BUS_API_TARGET_* 含该目标） */
    private boolean apiConfigured;

    /** api 目标 baseUrl（未配置 = null；管理端只读展示，不含 apiKey） */
    private String apiBaseUrl;
}
