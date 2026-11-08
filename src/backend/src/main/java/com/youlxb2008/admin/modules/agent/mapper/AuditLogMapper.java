package com.youlxb2008.admin.modules.agent.mapper;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.youlxb2008.admin.modules.agent.entity.AuditLog;

/**
 * agent 域审计日志 Mapper
 * 基于 MyBatis-Plus BaseMapper，提供基础 CRUD 能力
 * 注意：append-only 表，业务层禁止调用 update/delete 相关方法
 * <p>
 * 显式指定 bean 名 {@code agentAuditLogMapper}：管理端已有
 * {@code modules/system/mapper/AuditLogMapper}（默认 bean 名 auditLogMapper），
 * 两者同名会触发 ConflictingBeanDefinitionException，故 agent 域必须独立命名。
 */
@Repository("agentAuditLogMapper")
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
