package com.youlxb2008.admin.modules.bus.vo;

import java.util.Date;

import lombok.Data;

/**
 * 消息总线消息 VO（列表 + 详情共用；详情多 payload/lastError）
 * <p>
 * 对应管理端「消息总线」页列表行与详情弹窗（方案 §8）。
 */
@Data
public class BusMessageVO {

    /** 主键 */
    private Long id;

    /** 幂等键（信封「消息:」字段，收件方程序化去重键） */
    private String messageId;

    /** 生产者 agent id */
    private Long fromAgentId;

    /** 生产者展示名 */
    private String fromAgentName;

    /** 消费者 agent id */
    private Long toAgentId;

    /** 消费者展示名 */
    private String toAgentName;

    /** 类型：notify/alert */
    private String msgType;

    /** 场景标签 */
    private String topic;

    /** 标题 */
    private String title;

    /** 消息体 JSON（详情用；列表也可携带，量小） */
    private String payload;

    /** 会话标识（投递后回填实际值；空 = 待 dispatcher 生成） */
    private String sessionKey;

    /** 指定模型（空 = 收件人默认 preset） */
    private String model;

    /** 0待投递/1已送达/2已读(M2)/3失败 */
    private Integer status;

    /** 重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因（≤500 字符） */
    private String lastError;

    /** 送达时间 */
    private Date deliveredAt;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
