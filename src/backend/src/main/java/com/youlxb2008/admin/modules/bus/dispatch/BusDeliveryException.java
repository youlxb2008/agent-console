package com.youlxb2008.admin.modules.bus.dispatch;

/**
 * 投递失败异常（M1）
 * <p>
 * dispatcher 捕获后记 retry_count+1 + last_error（≤500 字符），
 * 指数退避 ≤5 次后 status=3（方案 §5.2）。连接失败同样折算为投递失败
 * （计入 retry，消息留表/队列等恢复，任务书 §2.5.5）。
 */
public class BusDeliveryException extends Exception {

	private static final long serialVersionUID = 1L;

	public BusDeliveryException(String message) {
		super(message);
	}
}
