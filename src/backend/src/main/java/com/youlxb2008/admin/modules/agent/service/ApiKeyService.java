package com.youlxb2008.admin.modules.agent.service;

import java.time.Duration;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.BusinessException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.dto.KeyUpdateReq;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.ApiKey;
import com.youlxb2008.admin.modules.agent.mapper.ApiKeyMapper;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * agent API Key Service
 * <p>
 * 核心职责：
 * <ul>
 *   <li>generateKey：生成明文 key（格式 {@code ac_{agentName}_{8位随机}}），
 *       SHA-256 哈希 + 明文前缀落库，明文只在返回值出现一次（不落库、不写日志）</li>
 *   <li>verifyKey：先查 Redis {@code ac:key:{keyHash}}（30s TTL），miss 回源 DB，
 *       返回 {@code {agentId, scopes, status}}</li>
 * </ul>
 * Redis 用 {@link StringRedisTemplate}；dev 环境无 Redis 时降级为纯 DB 校验（不缓存）。
 */
@Slf4j
@Service
public class ApiKeyService extends ServiceImpl<ApiKeyMapper, ApiKey> {

	/** Redis key 身份缓存前缀（契约 data-model.md §3） */
	private static final String KEY_CACHE_PREFIX = "ac:key:";

	/** 缓存 TTL 30s（key 作废/agent suspended 后最长 30s 内失效） */
	private static final Duration KEY_CACHE_TTL = Duration.ofSeconds(30);

	/** 明文前缀最大长度（t_api_key.key_prefix VARCHAR(16)） */
	private static final int KEY_PREFIX_MAX_LEN = 16;

	@Autowired(required = false)
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private AgentService agentService;

	/**
	 * 生成 API Key
	 * <p>
	 * 明文格式 {@code ac_{agentName}_{8位随机}}；SHA-256 哈希 + 明文前缀 + 明文落库；
	 * v4.1 起明文落库（key_plain），管理端可随时查看/复制历史 key；日志仍不打印明文。
	 *
	 * @param agentId   关联 t_agent.id
	 * @param scopes    权限列表（如 notes:write / audit:read）
	 * @param expiresAt 过期时间，null=永久
	 * @return 明文 key
	 */
	public String generateKey(Long agentId, List<String> scopes, Date expiresAt) {
		return doGenerate(agentId, scopes, expiresAt).getPlainKey();
	}

	/**
	 * 管理端：生成 API Key 并返回完整信息（契约 api.md §2，POST /keys）
	 * <p>
	 * 供管理端在响应中一次性返回 keyId/keyPrefix/明文，避免另行回查最新记录；
	 * 明文同时已落库，列表接口可再次获取（历史 key 可复制）。
	 *
	 * @param agentId   关联 t_agent.id
	 * @param scopes    权限列表
	 * @param expiresAt 过期时间，null=永久
	 * @return 生成结果（含 keyId/keyPrefix/明文）
	 */
	public GeneratedKey createKey(Long agentId, List<String> scopes, Date expiresAt) {
		return doGenerate(agentId, scopes, expiresAt);
	}

	/**
	 * 生成 API Key 核心逻辑（明文落库 + 随返回值给出，日志不打印明文）
	 */
	private GeneratedKey doGenerate(Long agentId, List<String> scopes, Date expiresAt) {
		Agent agent = agentService.getById(agentId);
		if (agent == null) {
			throw new BusinessException("agent 不存在");
		}

		String plainKey = "ac_" + agent.getName() + "_" + RandomUtil.randomString(8);
		String keyHash = DigestUtil.sha256Hex(plainKey);
		// 明文前缀：ac_{agentName}_ + 4 位随机（契约例子 ac_demo_ab12），截断到 16，不暴露完整 key
		String prefixBase = "ac_" + agent.getName() + "_" + RandomUtil.randomString(4);
		String keyPrefix = prefixBase.substring(0, Math.min(KEY_PREFIX_MAX_LEN, prefixBase.length()));

		ApiKey apiKey = new ApiKey();
		apiKey.setAgentId(agentId);
		apiKey.setKeyHash(keyHash);
		apiKey.setKeyPrefix(keyPrefix);
		// v4.1：明文同步落库（key_plain），管理端历史 key 列表可随时查看/复制
		apiKey.setKeyPlain(plainKey);
		apiKey.setScopes(toJson(scopes));
		apiKey.setStatus(1);
		apiKey.setExpiresAt(expiresAt);
		save(apiKey);

		// 审计/日志只记前缀与权限，不打印明文（落库明文仅供管理端页面展示）
		log.info("生成 API Key：agentId={} prefix={} scopes={}", agentId, keyPrefix, scopes);
		return new GeneratedKey(apiKey.getId(), keyPrefix, plainKey);
	}

	/**
	 * 生成结果（明文已落库，此处随响应给出便于前端立即展示）
	 */
	public static class GeneratedKey {
		private final Long keyId;
		private final String keyPrefix;
		private final String plainKey;

		public GeneratedKey(Long keyId, String keyPrefix, String plainKey) {
			this.keyId = keyId;
			this.keyPrefix = keyPrefix;
			this.plainKey = plainKey;
		}

		public Long getKeyId() {
			return keyId;
		}

		public String getKeyPrefix() {
			return keyPrefix;
		}

		public String getPlainKey() {
			return plainKey;
		}
	}

	/**
	 * 校验 API Key
	 * <p>
	 * 流程：SHA-256(明文) → 查 Redis {@code ac:key:{keyHash}}（30s TTL）→
	 * miss 回源 DB（校验 status=active、未过期）→ 写回缓存 → 返回身份信息。
	 *
	 * @param plainKey 明文 key
	 * @return {@code {agentId, scopes, status}}；无效 key（不存在/已吊销/已过期）返回 null
	 */
	public KeyIdentity verifyKey(String plainKey) {
		if (plainKey == null || plainKey.isBlank()) {
			return null;
		}
		String keyHash = DigestUtil.sha256Hex(plainKey);
		String cacheKey = KEY_CACHE_PREFIX + keyHash;

		// 1. 查 Redis 缓存
		if (redisTemplate != null) {
			try {
				String cached = redisTemplate.opsForValue().get(cacheKey);
				if (cached != null) {
					return fromJson(cached, KeyIdentity.class);
				}
			} catch (Exception e) {
				log.warn("读取 key 缓存失败，回源 DB：{}", e.getMessage());
			}
		}

		// 2. 回源 DB
		ApiKey apiKey = getOne(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getKeyHash, keyHash), true);
		if (apiKey == null) {
			return null;
		}
		if (apiKey.getStatus() == null || apiKey.getStatus() != 1) {
			return null;
		}
		if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().before(new Date())) {
			return null;
		}

		KeyIdentity identity = new KeyIdentity();
		identity.setKeyId(apiKey.getId());
		identity.setAgentId(apiKey.getAgentId());
		Agent agent = agentService.getById(apiKey.getAgentId());
		identity.setAgentName(agent != null ? agent.getName() : null);
		identity.setScopes(parseScopes(apiKey.getScopes()));
		identity.setStatus(apiKey.getStatus());

		// 3. 写回 Redis 缓存（30s TTL）
		if (redisTemplate != null) {
			try {
				redisTemplate.opsForValue().set(cacheKey, toJson(identity), KEY_CACHE_TTL);
			} catch (Exception e) {
				log.warn("写入 key 缓存失败：{}", e.getMessage());
			}
		}
		return identity;
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

	private <T> T fromJson(String json, TypeReference<T> typeRef) {
		try {
			return objectMapper.readValue(json, typeRef);
		} catch (JsonProcessingException e) {
			throw new BusinessException("JSON 反序列化失败", e);
		}
	}

	/**
	 * 解析 scopes 为字符串列表（兼容 H2/MySQL 两种编码形态）
	 * <p>
	 * t_api_key.scopes 是 JSON 列，实体侧以 String 承载：
	 * <ul>
	 *   <li>MySQL：直接存数组文本 {@code ["notes:write","audit:read"]}（单重编码，正常解析）；</li>
	 *   <li>H2：其对 JSON 列的 setString 会把参数文本再包一层引号，读回为
	 *       {@code "[\"notes:write\",\"audit:read\"]"}（双重编码：外层是 JSON String，内层才是数组文本）。</li>
	 * </ul>
	 * 因此按"先数组、后解包字符串再数组"的两级兜底解析，确保两种数据库都能取到正确权限列表。
	 *
	 * @param raw 数据库读回的 scopes 原始串（可能含 null/空白）
	 * @return 权限列表；空/非法时返回空列表（不抛错，兼容旧数据）
	 */
	public List<String> parseScopes(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String json = raw.trim();
		// 第一级：直接按数组解析（MySQL 及正常数据）
		try {
			return objectMapper.readValue(json, new TypeReference<List<String>>() {
			});
		} catch (JsonProcessingException directFail) {
			// 第二级：H2 双重编码——外层是 JSON String，先解出内层文本，再按数组解析
			try {
				String inner = objectMapper.readValue(json, String.class);
				return objectMapper.readValue(inner, new TypeReference<List<String>>() {
				});
			} catch (JsonProcessingException e) {
				log.warn("scopes 双重编码兜底解析失败，返回空列表({}): {}", e.getMessage(), json);
				return List.of();
			}
		}
	}

	/**
	 * 管理端：key 列表（契约 api.md §2，GET /keys?agentId=）
	 * <p>
	 * 只返回识别字段（prefix/scopes/status/lastUsedAt 等），由 VO 层丢弃 hash/明文。
	 *
	 * @param agentId 按 agent 过滤，null=全部
	 * @return key 列表（含已吊销，不含已逻辑删除）
	 */
	public List<ApiKey> listByAgent(Long agentId) {
		var wrapper = Wrappers.<ApiKey>lambdaQuery();
		if (agentId != null) {
			wrapper.eq(ApiKey::getAgentId, agentId);
		}
		return list(wrapper.orderByDesc(ApiKey::getCreateTime));
	}

	/**
	 * 管理端：统计某 agent 的 active key 数（列表聚合用）
	 */
	public long countActive(Long agentId) {
		return count(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getAgentId, agentId).eq(ApiKey::getStatus, 1));
	}

	/**
	 * 管理端：某 agent 最近一次 key 使用时间（列表聚合用）
	 *
	 * @return max(last_used_at)；无使用记录返回 null
	 */
	public Date latestUsedAt(Long agentId) {
		ApiKey key = getOne(Wrappers.<ApiKey>lambdaQuery().eq(ApiKey::getAgentId, agentId)
				.isNotNull(ApiKey::getLastUsedAt).orderByDesc(ApiKey::getLastUsedAt).last("limit 1"), true);
		return key != null ? key.getLastUsedAt() : null;
	}

	/**
	 * 管理端：修改 API Key（契约 api.md §2，PUT /keys/{id}，M5.4 新增）
	 * <p>
	 * 语义：null = 不修改（对齐 M5.3 due_date 的 PATCH 范式）；两项都为 null → 400；
	 * key 不存在 → 404；已吊销（status=0）→ 400「已吊销 key 不可修改」。
	 * scopes 非空时整体覆盖（{@link #toJson}，与 createKey 一致，不做服务端白名单校验——
	 * 前端 scope 选择器已限定可选值）；expiresAt 非空时覆盖。
	 * 不支持「清空过期时间改永久」——需要改永久就吊销重发。
	 *
	 * @param keyId t_api_key.id
	 * @param req   修改请求（scopes/expiresAt 均可选）
	 */
	public void updateKey(Long keyId, KeyUpdateReq req) {
		boolean hasScopes = req.getScopes() != null && !req.getScopes().isEmpty();
		if (!hasScopes && req.getExpiresAt() == null) {
			throw new BadRequestException("至少修改一项（scopes / expiresAt）");
		}
		ApiKey key = getById(keyId);
		if (key == null) {
			throw new NotFoundException("API Key 不存在");
		}
		if (key.getStatus() != null && key.getStatus() == 0) {
			throw new BadRequestException("已吊销 key 不可修改");
		}
		if (hasScopes) {
			key.setScopes(toJson(req.getScopes()));
		}
		if (req.getExpiresAt() != null) {
			key.setExpiresAt(req.getExpiresAt());
		}
		key.setUpdateTime(new Date());
		updateById(key);
		log.info("修改 API Key：keyId={} scopes={} expiresAt={}", keyId, req.getScopes(), req.getExpiresAt());
	}

	/**
	 * 管理端：物理删除单个 key（契约 api.md §2，DELETE /keys/{id}，M5.4-R1 新增）
	 * <p>
	 * 硬删除语义：{@code removeById} 物理 DELETE，不可恢复（吊销概念已整体下线，
	 * key 失效一律走删除）；key 不存在（含重复删除）抛业务异常。
	 * 审计由 Controller 记录（action=key.delete）。
	 *
	 * @param keyId t_api_key.id
	 */
	public void deleteKey(Long keyId) {
		ApiKey key = getById(keyId);
		if (key == null) {
			throw new BusinessException("API Key 不存在");
		}
		removeById(keyId);
		log.info("物理删除 API Key：keyId={} agentId={} prefix={}", keyId, key.getAgentId(), key.getKeyPrefix());
	}
}
