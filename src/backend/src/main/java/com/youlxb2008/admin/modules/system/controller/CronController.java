package com.youlxb2008.admin.modules.system.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.youlxb2008.admin.modules.system.entity.CronJob;
import com.youlxb2008.admin.modules.system.entity.CronLog;
import com.youlxb2008.admin.modules.system.manager.CronManager;
import com.youlxb2008.admin.modules.system.service.CronHandlerRegistry;
import com.youlxb2008.admin.modules.system.service.CronJobService;
import com.youlxb2008.admin.modules.system.service.CronLogService;
import com.youlxb2008.admin.modules.system.vo.CronJobVO;
import com.youlxb2008.admin.modules.system.vo.CronLogVO;
import com.youlxb2008.admin.utils.ResultVO;
import com.youlxb2008.admin.utils.jwt.annotation.RequiresPermissions;
import com.youlxb2008.admin.utils.page.PageSearch;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务管理 Controller
 * <p>
 * 注入规则（遵循 docs/架构设计/manager-layer.md）：
 * - Controller 可注入 Manager 和 Service
 * - 纯 CRUD 走 Service，业务编排走 Manager
 */
@RestController
@RequestMapping(path = "/system/cron")
@Slf4j
@Tag(name = "定时任务", description = "Cron任务管理/执行日志")
public class CronController {

	@Autowired
	private CronManager cronManager;

	@Autowired
	private CronJobService cronJobService;

	@Autowired
	private CronLogService cronLogService;

	@Autowired
	private CronHandlerRegistry handlerRegistry;

	/**
	 * 分页查询定时任务列表
	 */
	@Operation(summary = "分页查询定时任务列表")
	@RequiresPermissions("system:cron")
	@PostMapping("/list")
	public ResultVO list(CronJob cronJob, PageSearch page) {
		QueryWrapper<CronJob> queryWrapper = new QueryWrapper<CronJob>(cronJob);
		IPage<CronJob> pageData = cronJobService.page(page.toDBPage(), queryWrapper);
		// Entity → VO 转换，避免 Entity 出栈泄露内部字段
		IPage<CronJobVO> voPage = pageData.convert(this::convertToVO);
		return ResultVO.getSuccessVO("查询列表成功").setData(voPage);
	}

	/**
	 * 根据ID查询任务详情
	 */
	@Operation(summary = "查询任务详情")
	@RequiresPermissions("system:cron")
	@GetMapping("/getById")
	public ResultVO getById(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		CronJob cronJob = cronJobService.getById(id);
		if (cronJob == null) {
			return ResultVO.getFailedVO("CronJob not found");
		}
		// Entity → VO 转换后返回
		return ResultVO.getSuccessVO("查询详情成功").setData(convertToVO(cronJob));
	}

	/**
	 * 新增定时任务
	 */
	@Operation(summary = "新增定时任务")
	@RequiresPermissions("system:cron")
	@PostMapping("/add")
	public ResultVO add(@RequestBody CronJob cronJob) {
		try {
			cronManager.addJob(cronJob);
			return ResultVO.getSuccessVO("保存成功");
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/**
	 * 修改定时任务
	 */
	@Operation(summary = "修改定时任务")
	@RequiresPermissions("system:cron")
	@PostMapping("/update")
	public ResultVO update(@RequestBody CronJob cronJob) {
		try {
			cronManager.updateJob(cronJob);
			return ResultVO.getSuccessVO("修改成功");
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/**
	 * 根据ID删除定时任务
	 */
	@Operation(summary = "删除定时任务")
	@RequiresPermissions("system:cron")
	@GetMapping("/delete")
	public ResultVO delete(Long id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		try {
			cronManager.deleteJob(id);
			return ResultVO.getSuccessVO("删除成功");
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/**
	 * 手动触发定时任务执行
	 */
	@Operation(summary = "手动触发任务执行")
	@RequiresPermissions("system:cron")
	@PostMapping("/run")
	public ResultVO run(Integer id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		try {
			CronLog logEntry = cronManager.runJob(id);
			return ResultVO.getSuccessVO("任务执行完成").setData(logEntry);
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/**
	 * 切换任务启用/暂停状态
	 */
	@Operation(summary = "切换任务启用/暂停状态")
	@RequiresPermissions("system:cron")
	@PostMapping("/toggleStatus")
	public ResultVO toggleStatus(Integer id) {
		if (id == null) {
			return ResultVO.getFailedVO("ID is required");
		}
		try {
			CronJob job = cronManager.toggleStatus(id);
			// Entity → VO 转换后返回
			return ResultVO.getSuccessVO(job.getStatus() == 1 ? "已启用" : "已暂停").setData(convertToVO(job));
		} catch (IllegalArgumentException e) {
			return ResultVO.getFailedVO(e.getMessage());
		}
	}

	/**
	 * 分页查询任务执行日志
	 */
	@Operation(summary = "查询任务执行日志")
	@RequiresPermissions("system:cron")
	@PostMapping("/logs")
	public ResultVO logs(Long jobId, Integer logStatus, PageSearch page) {
		QueryWrapper<CronLog> queryWrapper = new QueryWrapper<CronLog>();
		if (jobId != null) {
			queryWrapper.eq("job_id", jobId);
		}
		if (logStatus != null) {
			queryWrapper.eq("status", logStatus);
		}
		queryWrapper.orderByDesc("created_at");
		IPage<CronLog> pageData = cronLogService.page(page.toDBPage(), queryWrapper);
		// Entity → VO 转换，避免 Entity 出栈泄露内部字段
		IPage<CronLogVO> voPage = pageData.convert(this::convertLogToVO);
		return ResultVO.getSuccessVO("查询日志成功").setData(voPage);
	}

	/**
	 * 清空指定任务的执行日志
	 */
	@Operation(summary = "清空任务执行日志")
	@RequiresPermissions("system:cron")
	@PostMapping("/clearLogs")
	public ResultVO clearLogs(Integer taskId) {
		if (taskId == null) {
			return ResultVO.getFailedVO("taskId is required");
		}
		int count = cronLogService.deleteLogsByJobId(taskId);
		return ResultVO.getSuccessVO("已清空 " + count + " 条日志");
	}

	/**
	 * 获取所有可用的处理器列表（供前端选择器使用）
	 */
	@Operation(summary = "获取可用处理器列表")
	@RequiresPermissions("system:cron")
	@GetMapping("/handlers")
	public ResultVO handlers() {
		return ResultVO.getSuccessVO("查询成功").setData(handlerRegistry.getHandlerList());
	}

	/**
	 * 定时任务实体转视图对象
	 * @param cronJob 任务实体
	 * @return 任务 VO（出参）
	 */
	private CronJobVO convertToVO(CronJob cronJob) {
		CronJobVO vo = new CronJobVO();
		vo.setId(cronJob.getId());
		vo.setName(cronJob.getName());
		vo.setCronExpr(cronJob.getCronExpr());
		vo.setHandlerClass(cronJob.getHandlerClass());
		vo.setParams(cronJob.getParams());
		vo.setStatus(cronJob.getStatus());
		vo.setCreateTime(cronJob.getCreatedAt());
		return vo;
	}

	/**
	 * 定时任务日志实体转视图对象
	 * @param cronLog 日志实体
	 * @return 日志 VO（出参）
	 */
	private CronLogVO convertLogToVO(CronLog cronLog) {
		CronLogVO vo = new CronLogVO();
		vo.setId(cronLog.getId());
		vo.setJobId(cronLog.getJobId());
		vo.setStatus(cronLog.getStatus());
		vo.setResult(cronLog.getResult());
		vo.setDuration(cronLog.getDuration());
		vo.setCreateTime(cronLog.getCreatedAt());
		return vo;
	}
}
