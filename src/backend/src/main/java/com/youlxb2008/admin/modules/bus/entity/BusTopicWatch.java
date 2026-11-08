package com.youlxb2008.admin.modules.bus.entity;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 总线 topic 等待段告警状态（bus-watchdog-fuse）
 * <p>
 * 保险丝扫描器维护，一行 = 一个 topic 的等待段去重状态：
 * <ul>
 *   <li>alerted_message_id = 当前等待段锚点（末条消息 id，恒非空）——同一等待段
 *       （同一末条消息）只告警一次；topic 出现任何新消息即新等待段（锚点变化）可再告警；</li>
 *   <li>alert_count = 该 topic 累计投递告警次数；0 = 当前等待段发现时
 *       BUS_ALERT_SESSION 未配置仅 warn 过（不重复刷 warn，env 配置后同段可升级补投）；</li>
 *   <li>等待段结束（回音/系统消息收尾/自投线程）由扫描器删行销案。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("t_bus_topic_watch")
public class BusTopicWatch {

    private static final long serialVersionUID = 1L;

    /** 主键 ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 原始 topic（无 [fuse] 前缀），唯一 */
    private String topic;

    /** 已告警等待段的最后一条消息 id（当前等待段锚点，恒非空） */
    private Long alertedMessageId;

    /** 该 topic 累计投递告警次数；0=发现时 BUS_ALERT_SESSION 未配置仅 warn */
    private Integer alertCount;

    /** 最近一次告警时间 */
    private Date lastAlertAt;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
