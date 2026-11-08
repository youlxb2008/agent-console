package com.youlxb2008.admin.modules.activity.job;

import java.time.ZoneId;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.modules.activity.service.ActivityService;

import lombok.extern.slf4j.Slf4j;

/**
 * 活动流保留期清理任务（M6.1 建立；M6.2 起保留策略 30 → 14 天）
 * <p>
 * 采用 ThreadPoolTaskScheduler 手动调度（不依赖 @EnableScheduling，
 * 照 {@code AuditFlushJob} 范式）：应用就绪后按 cron {@code 0 30 3 * * ?} 每日 03:30
 * （Asia/Shanghai，容器 UTC 环境须显式指定时区）执行
 * {@code DELETE FROM t_activity WHERE create_time < NOW() - INTERVAL 14 DAY}，
 * 日志记录删除行数。
 * <p>
 * M6.2 缩窗原因：工具级（tool_call）数据量约为 turn 级 20 倍（重度 turn 一次 40~100 行），
 * 14 天窗口内单 agent 百万行级上限；现有索引 (agent_id, create_time) + (session_key) 可承载。
 */
@Slf4j
@Component
public class ActivityCleanupJob {

	/** 清理周期：每日 03:30（cron 秒 分 时 日 月 周） */
	private static final String CRON = "0 30 3 * * ?";

	/** 调度时区 ID：业务时区 Asia/Shanghai（容器默认 UTC，须显式指定） */
	private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

	/** 保留窗口：14 天（M6.2 起，原 30 天；工具级数据量约为 turn 级 20 倍） */
	private static final int RETAIN_DAYS = 14;

	private final ActivityService activityService;
	private final ThreadPoolTaskScheduler taskScheduler;

	public ActivityCleanupJob(ActivityService activityService) {
		this.activityService = activityService;
		this.taskScheduler = new ThreadPoolTaskScheduler();
		this.taskScheduler.setPoolSize(1);
		this.taskScheduler.setThreadNamePrefix("activity-cleanup-");
		this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
		this.taskScheduler.initialize();
	}

	/**
	 * 应用就绪后启动每日 03:30 清理任务
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		CronTrigger trigger = new CronTrigger(CRON, TZ);
		taskScheduler.schedule(this::cleanup, trigger);
		log.info("活动流清理任务已启动（每日 {} Asia/Shanghai，保留 {} 天）", CRON, RETAIN_DAYS);
	}

	private void cleanup() {
		try {
			long threshold = System.currentTimeMillis() - RETAIN_DAYS * 24L * 3600 * 1000;
			int deleted = activityService.cleanupBefore(new java.util.Date(threshold));
			log.info("活动流清理完成：删除 {} 条（{} 天前）", deleted, RETAIN_DAYS);
		} catch (Exception e) {
			log.error("活动流清理失败：{}", e.getMessage(), e);
		}
	}
}
