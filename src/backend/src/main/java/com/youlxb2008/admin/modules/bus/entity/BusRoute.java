package com.youlxb2008.admin.modules.bus.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 总线投递通道方向级路由（bus-route-channel）
 * <p>
 * 对应数据库表 t_bus_route。按方向（from agent → to agent）配置投递通道：
 * ws（gateway 常连接，存量默认）/ api（HTTP 直调目标 nanobot OpenAI 兼容 API）。
 * <p>
 * 语义：无配置行 = ws（向后兼容，存量行为不变）；有行按 channel 走；
 * from = to（自投方向）允许配置（bus-ui-v2 放开，自投消息不参与熔断监控）。
 * 时间列名 create_time/update_time（与 agent 域一致），不继承 BaseEntity。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_bus_route")
public class BusRoute {

    /** 投递通道：gateway WS（存量默认，无配置行等价 ws） */
    public static final String CHANNEL_WS = "ws";

    /** 投递通道：HTTP 直调目标 OpenAI 兼容 API */
    public static final String CHANNEL_API = "api";

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源 agent id（t_agent.id） */
    private Long fromAgentId;

    /** 目标 agent id（t_agent.id） */
    private Long toAgentId;

    /** 投递通道：ws / api */
    private String channel;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
