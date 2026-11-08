package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 请求过于频繁异常（429，Too Many Requests）
 * <p>
 * 用于 agent 消息总线频控（M1，方案 §3.2）：分钟频控（每生产者 ≤600 条/min）与
 * 日频控（每生产者 ≤10000 条/天）任一超限 → 429。
 * 由 {@link com.youlxb2008.admin.exceptions.handler.BaseExceptionHandler#handlerGlobalException}
 * 统一转为 HTTP 429 响应。
 */
public class TooManyRequestsException extends BaseException {

	private static final long serialVersionUID = 1L;

	public TooManyRequestsException(String message) {
		super(ResponseCodeEnum.TOO_MANY_REQUESTS, message);
		this.setLog(false);
	}
}
