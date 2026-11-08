package com.youlxb2008.admin.exceptions;

import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;

/**
 * 无法处理实体异常（422，Unprocessable Entity）
 * <p>
 * 用于 PATCH 笔记冲突检测（M3.7）：改后元组 (notebookId, dirPath, title) 与
 * 同 notebook 下其他笔记（排除自身 nid）重复 → 422（防止 upsert 锚点歧义）。
 * 由 {@link com.youlxb2008.admin.exceptions.handler.BaseExceptionHandler#handlerGlobalException}
 * 统一转为 HTTP 422 响应。
 */
public class UnprocessableEntityException extends BaseException {

	private static final long serialVersionUID = 1L;

	public UnprocessableEntityException(String message) {
		super(ResponseCodeEnum.UNPROCESSABLE_ENTITY, message);
		this.setLog(false);
	}
}
