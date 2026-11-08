package com.youlxb2008.admin.modules.system.manager;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.youlxb2008.admin.modules.system.entity.CronJob;
import com.youlxb2008.admin.modules.system.entity.CronLog;
import com.youlxb2008.admin.modules.system.mapper.CronJobMapper;
import com.youlxb2008.admin.modules.system.mapper.CronLogMapper;
import com.youlxb2008.admin.modules.system.service.CronHandlerRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务调度引擎
 * <p>
 * 底层基础设施，不注入任何 Service，只使用 Mapper 直接操作数据库，
 * 避免与 Service 层形成循环依赖。
 * <p>
 * 核心职责：
 *   1. 应用启动后从数据库加载所有启用的任务，动态注册 cron 调度
 *   2. 提供添加/删除任务的调度管理能力
 *   3. 执行任务时调用对应的 handler，并记录执行日志
 *   4. 并发控制：通过内存 Set 记录正在执行的任务 ID
 */
@Slf4j
@Component
public class CronScheduler {

	private final CronJobMapper cronJobMapper;
	private final CronLogMapper cronLogMapper;
	private final CronHandlerRegistry handlerRegistry;
	private final ThreadPoolTaskScheduler taskScheduler;

	/** 正在执行的任务 ID 集合（用于并发控制） */
	private final Set<Integer> runningJobs = new HashSet<>();

	/** 已注册的调度任务 Future，用于取消 */
	private final ConcurrentHashMap<Integer, java.util.concurrent.ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

	public CronScheduler(CronJobMapper cronJobMapper, CronLogMapper cronLogMapper,
			CronHandlerRegistry handlerRegistry) {
		this.cronJobMapper = cronJobMapper;
		this.cronLogMapper = cronLogMapper;
		this.handlerRegistry = handlerRegistry;

		// 初始化线程池调度器
		this.taskScheduler = new ThreadPoolTaskScheduler();
		this.taskScheduler.setPoolSize(4);
		this.taskScheduler.setThreadNamePrefix("cron-exec-");
		this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
		this.taskScheduler.initialize();
	}

	/**
	 * 应用启动后加载所有启用的任务并注册调度
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		QueryWrapper<CronJob> wrapper = new QueryWrapper<CronJob>().eq("status", 1);
		var jobs = cronJobMapper.selectList(wrapper);
		log.info("加载 {} 个启用的定时任务", jobs.size());
		for (CronJob job : jobs) {
			addCronJob(job);
		}
	}

	/**
	 * 添加一个 cron 调度任务
	 */
	public void addCronJob(CronJob job) {
		// 如果已存在，先取消
		removeCronJob(job.getId());

		if (job.getCronExpr() == null || job.getCronExpr().isBlank()) {
			log.warn("任务「{}」cron 表达式为空，跳过注册", job.getName());
			return;
		}

		try {
			CronTrigger trigger = new CronTrigger(job.getCronExpr());
			java.util.concurrent.ScheduledFuture<?> future = taskScheduler.schedule(() -> {
				try {
					executeJob(job.getId());
				} catch (Exception e) {
					log.error("任务「{}」执行异常：{}", job.getName(), e.getMessage(), e);
				}
			}, trigger);

			scheduledFutures.put(job.getId(), future);
			log.info("注册定时任务「{}」({})", job.getName(), job.getCronExpr());
		} catch (IllegalArgumentException e) {
			log.error("注册定时任务「{}」失败，cron 表达式无效：{}", job.getName(), job.getCronExpr());
		}
	}

	/**
	 * 删除一个 cron 调度任务
	 */
	public void removeCronJob(Integer jobId) {
		java.util.concurrent.ScheduledFuture<?> future = scheduledFutures.remove(jobId);
		if (future != null) {
			future.cancel(false);
			log.info("移除定时任务调度 [ID={}]", jobId);
		}
	}

	/**
	 * 执行指定任务（核心执行逻辑）
	 * <p>
	 * 流程：
	 *   1. 并发检查
	 *   2. 从数据库重新读取最新任务配置
	 *   3. 创建执行日志记录
	 *   4. 调用 handler 执行业务逻辑
	 *   5. 更新日志状态
	 *
	 * @param jobId 任务 ID
	 * @return 执行日志
	 */
	public CronLog executeJob(Integer jobId) {
		// 并发控制
		synchronized (runningJobs) {
			if (runningJobs.contains(jobId)) {
				log.warn("任务 [ID={}] 正在执行中，跳过本次触发", jobId);
				CronLog skipLog = new CronLog();
				skipLog.setJobId(jobId);
				skipLog.setStatus(0); // 0=失败
				skipLog.setResult("任务正在执行中，跳过本次触发");
				skipLog.setDuration(0L);
				return skipLog;
			}
			runningJobs.add(jobId);
		}

		// 读取最新任务配置
		CronJob job = cronJobMapper.selectById(jobId);
		if (job == null) {
			synchronized (runningJobs) { runningJobs.remove(jobId); }
			throw new IllegalArgumentException("任务 ID=" + jobId + " 不存在");
		}

		// 创建执行日志
		CronLog logEntry = new CronLog();
		logEntry.setJobId(job.getId());
		logEntry.setStatus(2); // 2=执行中（暂用，最终会更新为 1=成功 或 0=失败）
		logEntry.setResult(null);
		cronLogMapper.insert(logEntry);

		long startTime = System.currentTimeMillis();

		try {
			// 查找并执行 handler
			CronHandlerRegistry.CronHandlerFn handlerFn = handlerRegistry.getHandler(job.getHandlerClass());
			if (handlerFn == null) {
				throw new RuntimeException("处理器「" + job.getHandlerClass() + "」未注册");
			}

			String result = handlerFn.execute(job.getParams());

			// 特殊处理：cleanExpiredLogs 需要在此执行实际删除
			if (result != null && result.startsWith("CLEAN_LOGS:")) {
				int days = Integer.parseInt(result.substring("CLEAN_LOGS:".length()));
				LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
				QueryWrapper<CronLog> delWrapper = new QueryWrapper<CronLog>().lt("created_at", cutoff);
				int deleted = cronLogMapper.delete(delWrapper);
				result = "清理了 " + deleted + " 条过期日志（" + days + " 天前）";
			}

			// 更新日志为成功
			logEntry.setStatus(1); // 1=成功
			logEntry.setDuration(System.currentTimeMillis() - startTime);
			logEntry.setResult(result);
			cronLogMapper.updateById(logEntry);

			log.info("任务「{}」执行成功，耗时 {}ms", job.getName(), logEntry.getDuration());
			return logEntry;
		} catch (Exception e) {
			// 更新日志为失败
			logEntry.setStatus(0); // 0=失败
			logEntry.setDuration(System.currentTimeMillis() - startTime);
			logEntry.setResult(e.getMessage());
			cronLogMapper.updateById(logEntry);

			log.error("任务「{}」执行失败：{}", job.getName(), e.getMessage());
			return logEntry;
		} finally {
			synchronized (runningJobs) { runningJobs.remove(jobId); }
		}
	}
}
