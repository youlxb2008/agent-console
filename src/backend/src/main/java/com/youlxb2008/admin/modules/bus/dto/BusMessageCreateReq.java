package com.youlxb2008.admin.modules.bus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 生产端发消息请求（M1，方案 §4.1）
 * <p>
 * POST /api/agent/bus/messages（X-API-Key + scope bus:write）。
 * <p>
 * 安全红线：生产者身份（from）只从 X-API-Key 推导——本 DTO 刻意不声明
 * fromAgentId 字段，body 里传 fromAgentId 会被 Jackson 直接忽略（防伪造，
 * 单测覆盖：body 夹带 fromAgentId 落表 from 仍为 key 归属）。
 */
@Data
public class BusMessageCreateReq {

    /** 目标 agent id（必填；须存在且启用） */
    @NotNull(message = "toAgentId 不能为空")
    private Long toAgentId;

    /** 消息类型：notify/alert（白名单）；request M1 禁用 → 422 */
    @NotBlank(message = "type 不能为空")
    @Size(max = 16, message = "type 超长")
    private String type;

    /** 场景标签（必填，如 alpha-exception） */
    @NotBlank(message = "topic 不能为空")
    @Size(max = 100, message = "topic 超长（≤100）")
    private String topic;

    /** 标题（必填） */
    @NotBlank(message = "title 不能为空")
    @Size(max = 255, message = "title 超长（≤255）")
    private String title;

    /** 消息体（任意 JSON 对象；序列化后 ≤8KB，超限 422） */
    private Object payload;

    /**
     * 可选：正文兼容字段（busfix-1007）。payload 为空（null 或空白串）且本字段非 blank 时，
     * 自动映射为 payload={"content": <值>}；payload 已有值时本字段忽略（不合并）。
     * 背景：Spring 对请求体未知字段静默忽略，生产端误用顶层 content（应为 payload.content）
     * 曾致 payload 落表 NULL、接收方看不到正文（2026-10-07 交付回执丢失事故根因）。
     */
    private String content;

    /**
     * 可选：目标会话（复用指定总线会话）；空 = dispatcher 复用固定会话 bus-agent{fromId}-{type}。
     * 长度由 bus-* 正则（^bus-[A-Za-z0-9_:-]{1,56}$）在服务层校验（超长同样 422，
     * 不用 @Size——bean 校验失败走 BindException 是 HTTP 200 envelope，契约口径不统一）
     */
    private String sessionKey;

    /** 可选：指定本条消息处理模型；空 = 收件人默认 preset；须经目标路由 models 白名单校验 */
    @Size(max = 64, message = "model 超长（≤64）")
    private String model;

    /**
     * 可选：消息级回音时限（分钟，bus-watchdog-fuse）；空 = 走全局默认
     * （env BUS_FUSE_DEFAULT_MINUTES，缺省 30）；0 = 显式无需回音、不参与熔断
     * （bus-fuse-zero-optout）；1~1440 = 等待回音分钟数。超界（不在 0/1~1440）→ 422，
     * 在服务层校验（与 sessionKey/model 同口径，不走 bean 校验）。
     */
    private Integer expectReplyMinutes;
}
