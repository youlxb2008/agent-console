package com.youlxb2008.admin.modules.agent.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.modules.agent.entity.AuditLog;
import com.youlxb2008.admin.modules.agent.mapper.AuditLogMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * agent 域审计日志 Service
 * <p>
 * 硬规则：t_audit_log 为 append-only，禁止 UPDATE/DELETE（本类不提供任何 update/remove 业务方法）。
 * <p>
 * 写入链路：append 先写 Redis List {@code ac:audit:buffer}（JSON 字符串），
 * 由 {@code AuditFlushJob} 每 5s 批量刷入 t_audit_log；Redis 不可用时降级直接落库（审计不丢）。
 */
@Slf4j
@Service
public class AuditService extends ServiceImpl<AuditLogMapper, AuditLog> {

	/** 审计缓冲 Redis List key（契约 data-model.md §3） */
	private static final String AUDIT_BUFFER_KEY = "ac:audit:buffer";

	/** 单次 flush 批量大小 */
	private static final int FLUSH_BATCH_SIZE = 100;

	@Autowired(required = false)
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * 追加审计日志（append-only，含 denied 也记）
	 * <p>
	 * 先写 Redis List {@code ac:audit:buffer}，由 AuditFlushJob 定时批量落库；
	 * Redis 不可用或写入失败时降级直接落库，保证审计不丢。
	 *
	 * @param principal 操作主体（agent 名/用户名）
	 * @param action    操作类型（key.create / note.submit / auth.login ...）
	 * @param resource  操作对象（note id / key id ...）
	 * @param request   请求摘要 JSON（method/path/参数，脱敏）
	 * @param result    success / denied / error
	 * @param clientIp  客户端 IP
	 */
	public void append(String principal, String action, String resource, String request, String result,
			String clientIp) {
		AuditLog auditLog = new AuditLog();
		auditLog.setPrincipal(principal);
		auditLog.setAction(action);
		auditLog.setResource(resource);
		auditLog.setRequest(request);
		auditLog.setResult(result);
		auditLog.setClientIp(clientIp);
		auditLog.setCreateTime(new Date());

		if (redisTemplate != null) {
			try {
				redisTemplate.opsForList().rightPush(AUDIT_BUFFER_KEY, toJson(auditLog));
				return;
			} catch (Exception e) {
				log.warn("写入审计缓冲失败，降级直接落库：{}", e.getMessage());
			}
		}
		// 降级：直接落库
		save(auditLog);
	}

	/**
	 * 批量刷入 t_audit_log（AuditFlushJob 每 5s 调用）
	 * <p>
	 * 流程：LRANGE 取一批 → 批量插入 → 成功后 LTRIM 删除已处理部分。
	 * 插入失败时数据保留在 buffer，下次重试（审计宁可重复不可丢失）。
	 *
	 * @return 本次刷入条数
	 */
	public int flush() {
		if (redisTemplate == null) {
			return 0;
		}
		List<String> batch;
		try {
			batch = redisTemplate.opsForList().range(AUDIT_BUFFER_KEY, 0, FLUSH_BATCH_SIZE - 1);
		} catch (Exception e) {
			log.warn("读取审计缓冲失败：{}", e.getMessage());
			return 0;
		}
		if (batch == null || batch.isEmpty()) {
			return 0;
		}

		List<AuditLog> logs = new ArrayList<>();
		for (String json : batch) {
			try {
				logs.add(fromJson(json, AuditLog.class));
			} catch (Exception e) {
				log.warn("审计缓冲 JSON 解析失败，跳过：{}", e.getMessage());
			}
		}
		if (logs.isEmpty()) {
			// 全部解析失败：清掉这批，避免死循环
			redisTemplate.opsForList().trim(AUDIT_BUFFER_KEY, batch.size(), -1);
			return 0;
		}

		// 批量插入
		saveBatch(logs);
		// 成功后删除已处理部分
		redisTemplate.opsForList().trim(AUDIT_BUFFER_KEY, batch.size(), -1);
		return logs.size();
	}

	/**
	 * 分页查询审计日志（管理端 agent:audit:list）
	 *
	 * @param page      页码（从 1 开始）
	 * @param size      每页条数
	 * @param principal 操作主体过滤（可选）
	 * @param action    操作类型过滤（可选）
	 * @param result    结果过滤（可选）
	 * @return 分页结果
	 */
	public IPage<AuditLog> pageQuery(long page, long size, String principal, String action, String result) {
		return pageQuery(page, size, principal, action, result, null, null);
	}

	/**
	 * 分页查询审计日志（管理端 agent:audit:list，含时间范围筛选）
	 *
	 * @param page      页码（从 1 开始）
	 * @param size      每页条数
	 * @param principal 操作主体过滤（可选）
	 * @param action    操作类型过滤（可选）
	 * @param result    结果过滤（可选）
	 * @param startTime 起始时间（含，可选）
	 * @param endTime   结束时间（含，可选）
	 * @return 分页结果
	 */
	public IPage<AuditLog> pageQuery(long page, long size, String principal, String action, String result,
			Date startTime, Date endTime) {
		var wrapper = Wrappers.<AuditLog>lambdaQuery()
				.eq(principal != null && !principal.isBlank(), AuditLog::getPrincipal, principal)
				.eq(action != null && !action.isBlank(), AuditLog::getAction, action)
				.eq(result != null && !result.isBlank(), AuditLog::getResult, result)
				.ge(startTime != null, AuditLog::getCreateTime, startTime)
				.le(endTime != null, AuditLog::getCreateTime, endTime)
				.orderByDesc(AuditLog::getCreateTime);
		return page(new Page<>(page, size), wrapper);
	}

	private String toJson(Object obj) {
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (JsonProcessingException e) {
			throw new BusinessException("JSON 序列化失败", e);
		}
	}

	private <T> T fromJson(String json, Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (JsonProcessingException e) {
			throw new BusinessException("JSON 反序列化失败", e);
		}
	}
}
