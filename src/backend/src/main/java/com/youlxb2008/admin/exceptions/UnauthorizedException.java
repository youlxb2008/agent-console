package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 未认证异常，用于 JWT 认证失败场景
 * 替代 Shiro 的 UnauthenticatedException
 */
public class UnauthorizedException extends BaseException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String message) {
        super(message);
        setResponseCode(ResponseCodeEnum.UNAUTHORIZED);
        setLog(false);
    }
}
