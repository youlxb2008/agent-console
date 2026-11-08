package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 该异常框架层面设计上，日志会输出异常栈
 * @author ebyte
 *
 */
public class BusinessException extends BaseException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -183122280255687370L;

	public BusinessException(String message) {
		this(message, new Throwable(message));
	}

	public BusinessException(String message, Throwable cause) {
		super(message, cause);
		setResponseCode(ResponseCodeEnum.ERROR);
		setLog(true);
	}
}