package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 资源不存在异常（404）
 * <p>
 * 用于笔记 nid/path 不存在、越权（非本人资源按不存在处理，防枚举）等场景。
 * 由 {@link com.youlxb2008.admin.exceptions.handler.BaseExceptionHandler#handlerGlobalException}
 * 统一转为 HTTP 404 响应。
 */
public class NotFoundException extends BaseException {

	private static final long serialVersionUID = 1L;

	public NotFoundException(String message) {
		super(ResponseCodeEnum.NOT_FOUND, message);
		this.setLog(false);
	}
}
