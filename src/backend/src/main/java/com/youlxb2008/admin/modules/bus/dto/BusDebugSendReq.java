package com.youlxb2008.admin.modules.bus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 总线调试发送请求（v4.37 bus-sessionkey-fix，管理端调试工具）
 * <p>
 * POST /system/bus/debug/send（JWT + 权限 bus:message:send）。
 * 临时切换 from=1（管理员 agent）→ 目标方向路由为所选通道 → 转调总线发送 → finally 还原原路由。
 * sessionKey 原样传给总线校验链（白名单 from 可携裸 uuid / websocket:&lt;uuid&gt; / api:&lt;uuid&gt;），
 * 落点由 dispatcher 剥前缀保证。
 */
@Data
public class BusDebugSendReq {

    /** 目标 agent id（必填；须存在且启用，校验链兜底） */
    @NotNull(message = "toAgentId 不能为空")
    private Long toAgentId;

    /**
     * 目标会话：裸 uuid 或 websocket:&lt;uuid&gt; / api:&lt;uuid&gt;（uuid 格式）；
     * 也可 bus-* 总线命名空间（原样透传不剥前缀）
     */
    @NotBlank(message = "sessionKey 不能为空")
    @Size(max = 100, message = "sessionKey 超长（≤100）")
    private String sessionKey;

    /** 标题（可选；空 = 「总线调试」） */
    @Size(max = 255, message = "title 超长（≤255）")
    private String title;

    /** 调试内容（必填；payload 形态 {"content": ...} 入信封第三段） */
    @NotBlank(message = "content 不能为空")
    @Size(max = 6000, message = "content 超长（≤6000，payload 总限 8KB）")
    private String content;

    /** 投递通道（临时切换，发送后还原）：ws / api */
    @NotBlank(message = "channel 不能为空")
    private String channel;
}
