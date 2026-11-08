package com.youlxb2008.admin.modules.system.dto;

import lombok.Data;

@Data
public class UserTokenPair {
    private String accessToken;
    private String refreshToken;
    private long expires; // 毫秒时间戳
}