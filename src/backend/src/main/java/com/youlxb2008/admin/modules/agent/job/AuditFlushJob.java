package com.youlxb2008.admin.modules.agent.job;

import java.time.Duration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.modules.agent.service.AuditService;

import lombok.extern.slf4j.Slf4j;

/**
 * 审计缓冲刷盘任务
 * <p>
 * 采用 ThreadPoolTaskScheduler 手动调度（不依赖 @EnableScheduling）：
 * 应用就绪后每 5s 从 Redis List {@code ac:audit:buffer} 批量刷入 t_audit_log。
 * Redis 不可用（dev 降级）时 flush 直接返回 0，无副作用。
 */
@Slf4j
@Component
public class AuditFlushJob {

	/** 刷盘周期：5s */
	private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);

	private final AuditService auditService;
	private final ThreadPoolTaskScheduler taskScheduler;

	public AuditFlushJob(AuditService auditService) {
		this.auditService = auditService;
		this.taskScheduler = new ThreadPoolTaskScheduler();
		this.taskScheduler.setPoolSize(1);
		this.taskScheduler.setThreadNamePrefix("audit-flush-");
		this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
		this.taskScheduler.initialize();
	}

	/**
	 * 应用就绪后启动固定频率刷盘任务
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		taskScheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL);
		log.info("审计缓冲刷盘任务已启动（每 {}s）", FLUSH_INTERVAL.toSeconds());
	}

	private void flush() {
		try {
			int flushed = auditService.flush();
			if (flushed > 0) {
				log.info("审计缓冲刷入 {} 条", flushed);
			}
		} catch (Exception e) {
			log.error("审计缓冲刷入失败：{}", e.getMessage(), e);
		}
	}
}
