package com.youlxb2008.admin.modules.system.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.system.entity.CronJob;
import com.youlxb2008.admin.modules.system.mapper.CronJobMapper;

/**
 * 定时任务 Service 实现类
 * <p>
 * 纯 CRUD 职责，不注入 Manager / Scheduler。
 * 业务编排（runJob / toggleStatus / addJob 同步调度等）由 CronManager 负责。
 */
@Service
public class CronJobService extends ServiceImpl<CronJobMapper, CronJob> {
}
