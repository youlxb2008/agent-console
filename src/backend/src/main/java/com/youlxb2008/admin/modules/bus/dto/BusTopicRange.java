package com.youlxb2008.admin.modules.bus.dto;

import lombok.Data;

/**
 * 总线 topic 线程范围（bus-watchdog-fuse 扫描器专用）
 * <p>
 * 一行 = 一个 topic 的首末消息 id：首条（min id）确定线程发起方向
 * （from→to），末条（max id）用于超时判定与等待段去重。
 */
@Data
public class BusTopicRange {

    /** topic 名 */
    private String topic;

    /** 线程首条消息 id（min，方向基准） */
    private Long firstId;

    /** 线程末条消息 id（max，超时判定对象） */
    private Long lastId;
}
