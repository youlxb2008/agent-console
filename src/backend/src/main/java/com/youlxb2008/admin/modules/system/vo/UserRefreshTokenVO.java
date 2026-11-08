package com.youlxb2008.admin.modules.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线设备视图对象（Controller 出参）
 * 隐藏 tokenHash 等敏感字段，只暴露设备展示信息
 */
@Data
public class UserRefreshTokenVO {
    private Long id;
    /** 设备名称 */
    private String deviceName;
    /** IP 地址 */
    private String ipAddress;
    /** User-Agent */
    private String userAgent;
    /** 过期时间 */
    private LocalDateTime expiresAt;
    /** 创建（登录）时间 */
    private LocalDateTime createdAt;
    /** 是否为当前设备 */
    private boolean current;
}
