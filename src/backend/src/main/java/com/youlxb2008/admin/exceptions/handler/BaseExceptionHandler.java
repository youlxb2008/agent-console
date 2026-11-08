package com.youlxb2008.admin.exceptions.handler;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.youlxb2008.admin.exceptions.BaseException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.ForbiddenException;
import com.youlxb2008.admin.exceptions.ParamsException;
import com.youlxb2008.admin.exceptions.UnauthorizedException;
import com.youlxb2008.admin.exceptions.enums.ResponseCodeEnum;
import com.youlxb2008.admin.utils.ResultVO;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理类
 */
@RestControllerAdvice
@Slf4j
public class BaseExceptionHandler {

	/**
	 * 处理UnauthorizedException（JWT认证失败，401）
	 *
	 * @param response
	 * @param e
	 * @return
	 */
	@ExceptionHandler(UnauthorizedException.class)
	public ResultVO handlerUnauthorizedException(HttpServletResponse response, UnauthorizedException e) {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode("401");
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理ForbiddenException（权限不足403）
	 */
	@ExceptionHandler(ForbiddenException.class)
	public ResultVO handlerForbiddenException(HttpServletResponse response, ForbiddenException e) {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode("403");
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理ParamsException
	 *
	 * @param response
	 * @param e
	 * @return
	 */
	@ExceptionHandler(ParamsException.class)
	public ResultVO handlerParamsException(HttpServletResponse response, ParamsException e) {
		if (e.isLog()) {
			log.error("请求异常：", e);
		}
		response.setStatus(e.getResponseCode().getCode());
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(Objects.toString(e.getResponseCode().getCode()));
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理BaseException
	 *
	 * @param response
	 * @param e
	 * @return
	 */
	@ExceptionHandler(BusinessException.class)
	public ResultVO handlerBusinessException(HttpServletResponse response, BusinessException e) {
		log.error("请求异常：", e);
		response.setStatus(e.getResponseCode().getCode());
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(Objects.toString(e.getResponseCode().getCode()));
		resultVO.setMsg("服务异常");
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理BaseException
	 *
	 * @param response
	 * @param e
	 * @return
	 */
	@ExceptionHandler(BaseException.class)
	public ResultVO handlerGlobalException(HttpServletResponse response, BaseException e) {
		if (e.isLog()) {
			log.error("请求异常：", e);
		}
		response.setStatus(e.getResponseCode().getCode());
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(Objects.toString(e.getResponseCode().getCode()));
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理BindException
	 *
	 * @param e
	 * @return
	 */
	@ExceptionHandler(BindException.class)
	@ResponseStatus(HttpStatus.OK)
	public ResultVO handlerBindException(BindException e) {
		log.info(e.getMessage());
		BindingResult bindingResult = e.getBindingResult();
		FieldError fieldError = bindingResult.getFieldError();
		assert fieldError != null;
		String defaultMessage = fieldError.getDefaultMessage();
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(ResponseCodeEnum.BAD_REQUEST.getCode().toString());
		resultVO.setMsg(defaultMessage);
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理 IllegalArgumentException（参数校验失败，如"父级权限不存在"）
	 * 将 JDK 内置异常转为友好提示，避免前端看到"系统异常"
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResultVO handlerIllegalArgumentException(HttpServletResponse response, IllegalArgumentException e) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode("400");
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理 IllegalStateException（状态校验失败，如"存在子权限，无法删除"）
	 */
	@ExceptionHandler(IllegalStateException.class)
	public ResultVO handlerIllegalStateException(HttpServletResponse response, IllegalStateException e) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode("400");
		resultVO.setMsg(e.getMessage());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理缺失必填请求参数（如门户 search/tags/by-tag/recent 缺 notebookId → 400，契约 v4.0）
	 * 默认落 catch-all 会变 500，这里显式接管为 400
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResultVO handlerMissingRequestParam(HttpServletResponse response, MissingServletRequestParameterException e) {
		response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode("400");
		resultVO.setMsg("缺少必填参数: " + e.getParameterName());
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理Exception
	 *
	 * @param e
	 * @return
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ResultVO handlerException(Exception e) {
		log.error("请求异常：", e);
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(ResponseCodeEnum.ERROR.getCode().toString());
		resultVO.setMsg("系统异常");
		resultVO.setSuccess(false);
		return resultVO;
	}

	/**
	 * 处理静态资源 404（如浏览器自动请求的 /favicon.ico）
	 * <p>
	 * 这类异常不是业务错误，不应该打 ERROR 日志污染日志流。
	 * Spring 6 中 NoResourceFoundException 会被 DefaultHandlerExceptionResolver
	 * 以 WARN 级别打印整个堆栈，这里通过显式 @ExceptionHandler 接管并降级为 debug。
	 *
	 * @param e NoResourceFoundException
	 * @return 标准 404 响应体（与业务异常格式一致）
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ResultVO handleNoResourceFound(NoResourceFoundException e) {
		// 静态资源缺失属于正常情况（如 favicon.ico），降级为 debug
		log.debug("静态资源未找到: {}", e.getResourcePath());
		ResultVO resultVO = new ResultVO();
		resultVO.setCode(String.valueOf(HttpStatus.NOT_FOUND.value()));
		resultVO.setMsg("资源不存在");
		resultVO.setSuccess(false);
		return resultVO;
	}
}
