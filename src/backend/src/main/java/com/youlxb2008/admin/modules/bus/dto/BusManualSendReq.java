package com.youlxb2008.admin.modules.bus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端手动发消息请求（M1，方案 §4.2）
 * <p>
 * POST /system/bus/messages（JWT + 权限 bus:message:send）。
 * 演练 / e2e 工具：可任选 from 模拟生产者，走与生产 API 完全同一校验链
 * （type 白名单 / 频控按所选 from 照常 / sessionKey bus-* / model 白名单）。
 */
@Data
public class BusManualSendReq {

    /** 来源 agent id（必填，任选模拟生产者；须存在且启用） */
    @NotNull(message = "fromAgentId 不能为空")
    private Long fromAgentId;

    /** 目标 agent id（必填；须存在且启用） */
    @NotNull(message = "toAgentId 不能为空")
    private Long toAgentId;

    /** 消息类型：notify/alert（白名单）；request M1 禁用 → 422 */
    @NotBlank(message = "type 不能为空")
    @Size(max = 16, message = "type 超长")
    private String type;

    /** 场景标签（必填） */
    @NotBlank(message = "topic 不能为空")
    @Size(max = 100, message = "topic 超长（≤100）")
    private String topic;

    /** 标题（必填） */
    @NotBlank(message = "title 不能为空")
    @Size(max = 255, message = "title 超长（≤255）")
    private String title;

    /** 消息体（任意 JSON 对象；序列化后 ≤8KB） */
    private Object payload;

    /** 可选：正文兼容字段（busfix-1007，与生产 API 同口径）——payload 空时自动映射 payload={"content":…} */
    private String content;

    /** 可选：目标会话，bus-* 命名空间；白名单 from 另可 websocket:（服务层正则校验，超长 422） */
    private String sessionKey;

    /** 可选：指定处理模型，须经目标路由 models 白名单校验 */
    @Size(max = 64, message = "model 超长（≤64）")
    private String model;

    /** 可选：消息级回音时限（分钟，1~1440，bus-watchdog-fuse）；空 = 走全局默认 */
    private Integer expectReplyMinutes;
}
