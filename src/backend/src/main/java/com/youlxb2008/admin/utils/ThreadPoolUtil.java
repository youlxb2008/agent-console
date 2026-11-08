package com.youlxb2008.admin.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadPoolUtil {

	private static final ConcurrentHashMap<String, ThreadPoolExecutor> POOL_MAP = new ConcurrentHashMap<String, ThreadPoolExecutor>();
	private static final Integer DEFAULT_QUEUE_SIZE = 3000;
	private static final Integer DEFAULT_POOL_SIZE = 1;

	public static ExecutorService getInstance(String name, int initPoolSize, int initQueueSize) {
		if (POOL_MAP.containsKey(name)) {
			return POOL_MAP.get(name);
		}
		synchronized (ThreadPoolUtil.class) {
			if (POOL_MAP.containsKey(name)) {
				return POOL_MAP.get(name);
			}
			ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(initPoolSize, initPoolSize, 0L,
					TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(initQueueSize),
					new ThreadPoolExecutor.AbortPolicy());
			POOL_MAP.put(name, threadPoolExecutor);
			return threadPoolExecutor;
		}
	}

	public static ExecutorService getInstance(String name, int initPoolSize) {
		return getInstance(name, initPoolSize, DEFAULT_QUEUE_SIZE);
	}

	/**
	 * 默认为1线程的线程池，用于队列
	 * 
	 * @param name
	 * @return
	 */
	public static ExecutorService getInstance(String name) {
		return getInstance(name, DEFAULT_POOL_SIZE, DEFAULT_QUEUE_SIZE);
	}
}
