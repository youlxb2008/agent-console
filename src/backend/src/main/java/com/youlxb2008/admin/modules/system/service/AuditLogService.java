package com.youlxb2008.admin.modules.system.service;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlxb2008.admin.modules.system.entity.AuditLog;
import com.youlxb2008.admin.modules.system.mapper.AuditLogMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 审计日志 Service
 * 提供审计日志的写入与查询能力，由 AuditLogAspect 调用写入
 */
@Slf4j
@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLog> {
}
