package com.youlxb2008.admin.modules.system.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.system.entity.CronLog;
import com.youlxb2008.admin.modules.system.mapper.CronLogMapper;

/**
 * 定时任务执行日志 Service 实现类
 */
@Service
public class CronLogService extends ServiceImpl<CronLogMapper, CronLog> {

	/**
	 * 删除指定时间之前的日志
	 *
	 * @param cutoff 截止时间
	 * @return 删除的行数
	 */
	public int deleteLogsBefore(LocalDateTime cutoff) {
		QueryWrapper<CronLog> wrapper = new QueryWrapper<>();
		wrapper.lt("created_at", cutoff);
		return baseMapper.delete(wrapper);
	}

	/**
	 * 删除指定任务的所有日志
	 *
	 * @param jobId 任务ID
	 * @return 删除的行数
	 */
	public int deleteLogsByJobId(Integer jobId) {
		QueryWrapper<CronLog> wrapper = new QueryWrapper<>();
		wrapper.eq("job_id", jobId);
		return baseMapper.delete(wrapper);
	}
}
