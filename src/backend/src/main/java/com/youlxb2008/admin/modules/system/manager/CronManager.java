package com.youlxb2008.admin.modules.system.manager;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.youlxb2008.admin.modules.system.entity.CronJob;
import com.youlxb2008.admin.modules.system.entity.CronLog;
import com.youlxb2008.admin.modules.system.service.CronHandlerRegistry;
import com.youlxb2008.admin.modules.system.service.CronJobService;

import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务 Manager（业务编排层）
 * <p>
 * 按照 Manager 层架构规范：
 *   - 可注入 Service、Mapper
 *   - 不注入 Manager
 *   - 职责：跨 Service 编排、调度引擎同步、handler 校验
 * <p>
 * Controller 注入 CronManager 来完成所有非纯 CRUD 的业务操作
 */
@Slf4j
@Component
public class CronManager {

	@Autowired
	private CronJobService cronJobService;

	@Autowired
	private CronScheduler cronScheduler;

	@Autowired
	private CronHandlerRegistry handlerRegistry;

	/**
	 * 手动触发任务执行
	 *
	 * @param id 任务ID
	 * @return 执行日志
	 */
	public CronLog runJob(Integer id) {
		CronJob job = cronJobService.getById(id);
		if (job == null) {
			throw new IllegalArgumentException("任务不存在");
		}
		return cronScheduler.executeJob(id);
	}

	/**
	 * 切换任务启用/暂停状态
	 *
	 * @param id 任务ID
	 * @return 更新后的任务
	 */
	public CronJob toggleStatus(Integer id) {
		CronJob job = cronJobService.getById(id);
		if (job == null) {
			throw new IllegalArgumentException("任务不存在");
		}

		// 移除旧调度
		cronScheduler.removeCronJob(id);

		// 切换状态：1→0 暂停，0→1 启用
		job.setStatus(job.getStatus() == 1 ? 0 : 1);
		cronJobService.updateById(job);

		// 如果启用，重新注册调度
		if (job.getStatus() == 1) {
			cronScheduler.addCronJob(job);
		}

		return job;
	}

	/**
	 * 新增任务并同步调度引擎
	 */
	public void addJob(CronJob cronJob) {
		if (cronJob.getHandlerClass() != null && !handlerRegistry.hasHandler(cronJob.getHandlerClass())) {
			throw new IllegalArgumentException("处理器「" + cronJob.getHandlerClass() + "」未注册");
		}
		cronJobService.save(cronJob);
		if (cronJob.getStatus() != null && cronJob.getStatus() == 1) {
			cronScheduler.addCronJob(cronJob);
		}
		log.info("添加定时任务成功 cronJob={}", cronJob);
	}

	/**
	 * 修改任务并同步调度引擎
	 */
	public void updateJob(CronJob cronJob) {
		if (cronJob.getId() == null) {
			throw new IllegalArgumentException("ID is required");
		}
		if (cronJob.getHandlerClass() != null && !handlerRegistry.hasHandler(cronJob.getHandlerClass())) {
			throw new IllegalArgumentException("处理器「" + cronJob.getHandlerClass() + "」未注册");
		}
		cronJobService.updateById(cronJob);
		// 同步调度引擎：先移除，如果启用则重新注册
		cronScheduler.removeCronJob(cronJob.getId());
		CronJob updated = cronJobService.getById(cronJob.getId());
		if (updated.getStatus() != null && updated.getStatus() == 1) {
			cronScheduler.addCronJob(updated);
		}
		log.info("更新定时任务成功 cronJob={}", cronJob);
	}

	/**
	 * 删除任务并同步调度引擎
	 */
	public void deleteJob(Long id) {
		CronJob cronJob = cronJobService.getById(id);
		if (cronJob == null) {
			throw new IllegalArgumentException("任务不存在");
		}
		cronScheduler.removeCronJob(id.intValue());
		cronJobService.removeById(id);
		log.info("删除定时任务成功 cronJob={}", cronJob);
	}

	/**
	 * 校验 handler 是否已注册
	 */
	public boolean isHandlerValid(String handlerClass) {
		return handlerRegistry.hasHandler(handlerClass);
	}
}
