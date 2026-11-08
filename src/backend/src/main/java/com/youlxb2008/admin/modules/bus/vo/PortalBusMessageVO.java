package com.youlxb2008.admin.modules.bus.vo;

import java.util.Date;

import lombok.Data;

/**
 * 门户消息流转 VO（M1 门户包；匿名公开域，字段白名单）
 * <p>
 * portal 域匿名可访问，<b>严禁</b>出现 payload / sessionKey / model / lastError /
 * messageId / updateTime 等内部字段（防内部内容泄漏）；agent 名复用管理端解析口径
 * （displayName 优先回退 name）。
 */
@Data
public class PortalBusMessageVO {

    /** 主键（时间线排序键，倒序） */
    private Long id;

    /** 生产者 agent id（前端 hover 显示用） */
    private Long fromAgentId;

    /** 生产者展示名 */
    private String fromAgentName;

    /** 消费者 agent id（前端 hover 显示用） */
    private Long toAgentId;

    /** 消费者展示名 */
    private String toAgentName;

    /** 类型：notify/alert */
    private String msgType;

    /** 场景标签 */
    private String topic;

    /** 标题 */
    private String title;

    /** 状态：0待投递/1已送达/2已读(M2)/3失败 */
    private Integer status;

    /** 实际投递通道：ws/api；历史行 NULL（前端显「—」，bus-ui-v2） */
    private String channel;

    /** 重试次数（前端仅 &gt;0 展示） */
    private Integer retryCount;

    /** 送达时间 */
    private Date deliveredAt;

    /** 创建时间 */
    private Date createTime;
}
