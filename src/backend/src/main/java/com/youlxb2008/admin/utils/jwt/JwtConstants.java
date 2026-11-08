package com.youlxb2008.admin.utils.jwt;

/**
 * JWT 认证相关常量（路径、Header 名等）
 * <p>
 * 统一收口认证相关的魔法串，避免在 Filter / Config / 文档中各自维护导致"改一处漏两处"。
 */
public final class JwtConstants {

    /** Authorization Header 名 */
    public static final String HEADER = "Authorization";

    /** 登录接口路径（不需要 JWT 认证） */
    public static final String LOGIN_PATH = "/system/user/login";

    /** 刷新 token 接口路径（不需要 JWT 认证） */
    public static final String REFRESH_TOKEN_PATH = "/system/user/refresh-token";

    /** 登出接口路径（不需要 JWT 认证） */
    public static final String LOGOUT_PATH = "/system/user/logout";

    private JwtConstants() {
    }
}
