package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 该异常框架层面设计上，日志不会输出异常栈
 * @author ebyte
 *
 */
public class ParamsException extends BaseException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -183122280255687370L;

	public ParamsException(String message) {
		this(message, new Throwable(message));
	}

	public ParamsException(String message, Throwable cause) {
		super(message, cause);
		setResponseCode(ResponseCodeEnum.OK);
		setLog(false);
	}
}