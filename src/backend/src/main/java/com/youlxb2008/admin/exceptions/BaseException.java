package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class BaseException extends RuntimeException {
	private static final long serialVersionUID = 1321890145217732020L;
	protected ResponseCodeEnum responseCode;
	protected boolean isLog = true;

	public BaseException(ResponseCodeEnum responseCode, String message, Throwable cause) {
		super(message, cause);
		setResponseCode(responseCode);
	}
	
	public BaseException(ResponseCodeEnum responseCode, String message) {
		super(message);
		setResponseCode(responseCode);
	}

	public BaseException(String message) {
		super(message, new Throwable(message));
		setResponseCode(ResponseCodeEnum.ERROR);
	}

	public BaseException(String message, Throwable cause) {
		super(message, cause);
		setResponseCode(ResponseCodeEnum.ERROR);
	}

}
