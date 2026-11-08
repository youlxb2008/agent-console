package com.youlxb2008.admin.modules.system.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务处理器注册表
 * <p>
 * 集中管理所有可用的任务处理器。
 * 新增任务类型时，只需调用 register() 注册一个 handler 即可，
 * 调度引擎通过 handler 名称查找并执行对应的处理函数。
 */
@Slf4j
@Service
public class CronHandlerRegistry {

	/** 处理器函数式接口 */
	@FunctionalInterface
	public interface CronHandlerFn {
		String execute(String params) throws Exception;
	}

	/** 处理器注册项 */
	private static class HandlerEntry {
		final CronHandlerFn fn;
		final String description;

		HandlerEntry(CronHandlerFn fn, String description) {
			this.fn = fn;
			this.description = description;
		}
	}

	/** 处理器注册表：handler 名称 → 注册项 */
	private final Map<String, HandlerEntry> handlers = new HashMap<>();

	public CronHandlerRegistry() {
		// 注册内置处理器
		register("demoHello", this::demoHello, "示例任务：输出问候语");
		register("cleanExpiredLogs", this::cleanExpiredLogs, "清理过期的定时任务执行日志");
	}

	/**
	 * 注册一个任务处理器
	 *
	 * @param name        处理器名称（唯一标识）
	 * @param fn          处理器执行函数
	 * @param description 处理器描述（用于前端展示）
	 */
	public void register(String name, CronHandlerFn fn, String description) {
		if (handlers.containsKey(name)) {
			log.warn("处理器「{}」已被注册，将被覆盖", name);
		}
		handlers.put(name, new HandlerEntry(fn, description));
	}

	/**
	 * 获取处理器执行函数
	 *
	 * @param name 处理器名称
	 * @return 处理器执行函数，未找到时返回 null
	 */
	public CronHandlerFn getHandler(String name) {
		HandlerEntry entry = handlers.get(name);
		return entry != null ? entry.fn : null;
	}

	/**
	 * 获取所有已注册的处理器信息（供前端选择器使用）
	 */
	public List<Map<String, String>> getHandlerList() {
		List<Map<String, String>> list = new ArrayList<>();
		for (Map.Entry<String, HandlerEntry> e : handlers.entrySet()) {
			Map<String, String> item = new HashMap<>();
			item.put("name", e.getKey());
			item.put("description", e.getValue().description);
			list.add(item);
		}
		return list;
	}

	/**
	 * 检查处理器是否已注册
	 */
	public boolean hasHandler(String name) {
		return handlers.containsKey(name);
	}

	// ============ 内置处理器实现 ============

	/**
	 * 示例处理器：输出问候语
	 * 用于测试定时任务调度是否正常工作
	 */
	private String demoHello(String params) {
		String name = "World";
		if (params != null && !params.isBlank()) {
			try {
				// 尝试从 JSON 中提取 name 字段
				if (params.contains("\"name\"")) {
					name = params.replaceAll(".*\"name\"\\s*:\\s*\"([^\"]+)\".*", "$1");
				} else {
					name = params.trim();
				}
			} catch (Exception e) {
				name = params.trim();
			}
		}
		String message = "Hello, " + name + "! 当前时间："
				+ LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		log.info(message);
		return message;
	}

	/**
	 * 清理过期的定时任务执行日志
	 * 默认清理 30 天前的日志记录
	 * 返回特殊标记 CLEAN_LOGS:days，由调度引擎执行实际删除
	 */
	private String cleanExpiredLogs(String params) {
		int days = 30;
		if (params != null && !params.isBlank()) {
			try {
				if (params.contains("\"days\"")) {
					days = Integer.parseInt(params.replaceAll(".*\"days\"\\s*:\\s*(\\d+).*", "$1"));
				} else {
					days = Integer.parseInt(params.trim());
				}
			} catch (Exception ignored) {
			}
		}
		return "CLEAN_LOGS:" + days;
	}
}
