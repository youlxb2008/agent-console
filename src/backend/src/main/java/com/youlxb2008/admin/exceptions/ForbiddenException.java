package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 权限不足异常（403）
 * 用于 @RequiresPermissions / @RequiresRoles 校验失败时抛出
 */
public class ForbiddenException extends BaseException {
    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(ResponseCodeEnum.FORBIDDEN, message);
        this.setLog(false);
    }
}
