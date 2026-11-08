package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 请求参数非法异常（400）
 * <p>
 * 用于笔记路径/标题/内容黑名单校验失败等场景（契约 data-model.md §5.2，fail-closed）。
 * 由 {@link com.youlxb2008.admin.exceptions.handler.BaseExceptionHandler#handlerGlobalException}
 * 统一转为 HTTP 400 响应。
 */
public class BadRequestException extends BaseException {

	private static final long serialVersionUID = 1L;

	public BadRequestException(String message) {
		super(ResponseCodeEnum.BAD_REQUEST, message);
		this.setLog(false);
	}
}
