package com.youlxb2008.admin.utils.jwt.utils;


import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.youlxb2008.admin.utils.jwt.JwtConstants;

import cn.hutool.core.date.DateUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;


/**
 * JWT 工具类（基于 jjwt 0.12.x）
 * <p>
 * 统一使用 io.jsonwebtoken (jjwt) 实现 token 的生成、解析、校验。
 * 历史曾引入 com.auth0:java-jwt 用于免密解码，现已移除，避免双依赖造成的混淆。
 */
@Component
@Slf4j
public class JwtUtil {
    /** 签名密钥（脚手架默认值，生产部署请通过配置覆盖并替换为高强度随机串） */
    private static final String SECRET = "zxcvbnmfdasaererafafafafafafakjlkjalkfafadffdafadfafafaaafadfadfaf1234567890";
    /** 默认过期时间（分钟），7 天 */
    public static final int EXPIRE = 60 * 24 * 7;
    /** Authorization Header 名（统一引用 {@link JwtConstants#HEADER}） */
    public static final String HEADER = JwtConstants.HEADER;

    /**
     * 生成 jwt token（使用默认过期时间 {@link #EXPIRE}）
     * <p>
     * 保留此重载仅为兼容与本地调试；生产调用方应使用 {@link #generateToken(String, Date)} 显式传入过期时间。
     */
    public String generateToken(String username) {
        Date date = new Date();
        // 默认过期时间：当前时间 + EXPIRE 分钟
        Date expire = DateUtil.offsetMinute(date, EXPIRE);
        return generateToken(username, expire);
    }

    /**
     * 生成 jwt token（指定过期时间）
     *
     * @param username 用户名（同时作为 subject 和 username claim）
     * @param expire   过期时间
     */
    public String generateToken(String username, Date expire) {
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Date date = new Date();
        return Jwts.builder()
                .signWith(signingKey, Jwts.SIG.HS512)
                .header().add("typ", "JWT").and()
                .issuedAt(date)
                .subject(username)
                .expiration(expire)
                .claims(Map.of("username", username))
                .compact();
    }

    /**
     * 解析 token，返回 Claims（需校验签名）
     */
    public Claims getClaimsByToken(String token) {
        SecretKey signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 检查 token 是否过期
     *
     * @param expiration token 的过期时间
     * @return true 表示已过期
     */
    public boolean isTokenExpired(Date expiration) {
        return expiration.before(new Date());
    }

    /**
     * 从 token 中获取自定义字段值（如 username）
     * <p>
     * 注意：此方法会校验签名。如需免密解码（不校验签名），请直接调用 {@link #getClaimsByToken} 的无校验版本，
     * 但出于安全考虑，脚手架默认不做免密解码。
     *
     * @param token jwt token 字符串
     * @param field 字段名（如 "username"）
     * @return 字段值，解析失败返回 null
     */
    public String getClaimFiled(String token, String field) {
        try {
            Claims claims = getClaimsByToken(token);
            return claims.get(field, String.class);
        } catch (Exception e) {
            log.error("JwtUtil getClaimFiled error: ", e);
            return null;
        }
    }
}
