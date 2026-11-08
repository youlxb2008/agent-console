package com.youlxb2008.admin.modules.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youlxb2008.admin.exceptions.BadRequestException;
import com.youlxb2008.admin.exceptions.NotFoundException;
import com.youlxb2008.admin.modules.agent.dto.KeyUpdateReq;
import com.youlxb2008.admin.modules.agent.entity.Agent;
import com.youlxb2008.admin.modules.agent.entity.ApiKey;
import com.youlxb2008.admin.modules.agent.vo.KeyIdentity;

import cn.hutool.crypto.digest.DigestUtil;

/**
 * ApiKeyService 单元测试
 * 覆盖：generateKey 明文格式/哈希落库/明文只出现一次、verifyKey 缓存命中/回源 DB/无效 key
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private AgentService agentService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    @org.mockito.InjectMocks
    private ApiKeyService apiKeyService;

    private Agent agent;

    @BeforeEach
    void setUp() {
        agent = new Agent();
        agent.setId(1L);
        agent.setName("bravo");
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("generateKey：明文格式 ac_{name}_{8位随机}，SHA-256 哈希 + 前缀落库，明文只在返回值出现")
    void testGenerateKey() {
        doReturn(agent).when(agentService).getById(1L);
        doAnswer(inv -> {
            ((ApiKey) inv.getArgument(0)).setId(100L);
            return true;
        }).when(apiKeyService).save(any(ApiKey.class));

        String plainKey = apiKeyService.generateKey(1L, List.of("notes:write", "audit:read"), null);

        // 明文格式：ac_bravo_ + 8 位随机 = 17 字符
        assertNotNull(plainKey);
        assertTrue(plainKey.startsWith("ac_bravo_"));
        assertEquals(17, plainKey.length());

        // 落库字段：keyHash=SHA-256(明文)，keyPrefix 不暴露完整 key
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyService).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals(DigestUtil.sha256Hex(plainKey), saved.getKeyHash());
        assertTrue(saved.getKeyPrefix().startsWith("ac_bravo_"));
        assertTrue(saved.getKeyPrefix().length() <= 16);
        assertTrue(!saved.getKeyPrefix().equals(plainKey), "前缀不应暴露完整明文 key");
        assertEquals("[\"notes:write\",\"audit:read\"]", saved.getScopes());
        assertEquals(1, saved.getStatus());
        assertNull(saved.getExpiresAt());
    }

    @Test
    @DisplayName("generateKey：agent 不存在抛业务异常")
    void testGenerateKeyAgentNotFound() {
        doReturn(null).when(agentService).getById(99L);
        try {
            apiKeyService.generateKey(99L, List.of("notes:write"), null);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (com.youlxb2008.admin.exceptions.BusinessException e) {
            assertTrue(e.getMessage().contains("agent 不存在"));
        }
    }

    @Test
    @DisplayName("verifyKey：Redis 缓存命中直接返回，不回源 DB")
    void testVerifyKeyCacheHit() {
        KeyIdentity cached = new KeyIdentity();
        cached.setAgentId(1L);
        cached.setScopes(List.of("notes:write"));
        cached.setStatus(1);
        String cacheKey = "ac:key:" + DigestUtil.sha256Hex("ac_bravo_abcdefgh");
        when(valueOps.get(cacheKey)).thenReturn("{\"agentId\":1,\"scopes\":[\"notes:write\"],\"status\":1}");

        KeyIdentity identity = apiKeyService.verifyKey("ac_bravo_abcdefgh");

        assertNotNull(identity);
        assertEquals(1L, identity.getAgentId());
        assertEquals(List.of("notes:write"), identity.getScopes());
        assertEquals(1, identity.getStatus());
        // 缓存命中不应回源 DB
        verify(apiKeyService, never()).getOne(any(), anyBoolean());
    }

    @Test
    @DisplayName("verifyKey：缓存 miss 回源 DB，校验通过后写回缓存")
    void testVerifyKeyDbFallback() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(100L);
        apiKey.setAgentId(1L);
        apiKey.setKeyHash(DigestUtil.sha256Hex("ac_bravo_abcdefgh"));
        apiKey.setScopes("[\"notes:write\"]");
        apiKey.setStatus(1);
        apiKey.setExpiresAt(null);

        String cacheKey = "ac:key:" + apiKey.getKeyHash();
        when(valueOps.get(cacheKey)).thenReturn(null);
        doReturn(apiKey).when(apiKeyService).getOne(any(), anyBoolean());

        KeyIdentity identity = apiKeyService.verifyKey("ac_bravo_abcdefgh");

        assertNotNull(identity);
        assertEquals(1L, identity.getAgentId());
        assertEquals(List.of("notes:write"), identity.getScopes());
        assertEquals(1, identity.getStatus());
        // 写回缓存
        verify(valueOps).set(eq(cacheKey), anyString(), any());
    }

    @Test
    @DisplayName("verifyKey：key 不存在返回 null")
    void testVerifyKeyNotFound() {
        String cacheKey = "ac:key:" + DigestUtil.sha256Hex("ac_bravo_zzzzzzzz");
        when(valueOps.get(cacheKey)).thenReturn(null);
        doReturn(null).when(apiKeyService).getOne(any(), anyBoolean());

        assertNull(apiKeyService.verifyKey("ac_bravo_zzzzzzzz"));
    }

    @Test
    @DisplayName("verifyKey：key 已吊销（status=0）返回 null")
    void testVerifyKeyRevoked() {
        ApiKey apiKey = new ApiKey();
        apiKey.setAgentId(1L);
        apiKey.setKeyHash(DigestUtil.sha256Hex("ac_bravo_abcdefgh"));
        apiKey.setScopes("[]");
        apiKey.setStatus(0);

        String cacheKey = "ac:key:" + apiKey.getKeyHash();
        when(valueOps.get(cacheKey)).thenReturn(null);
        doReturn(apiKey).when(apiKeyService).getOne(any(), anyBoolean());

        assertNull(apiKeyService.verifyKey("ac_bravo_abcdefgh"));
    }

    @Test
    @DisplayName("verifyKey：key 已过期返回 null")
    void testVerifyKeyExpired() {
        ApiKey apiKey = new ApiKey();
        apiKey.setAgentId(1L);
        apiKey.setKeyHash(DigestUtil.sha256Hex("ac_bravo_abcdefgh"));
        apiKey.setScopes("[]");
        apiKey.setStatus(1);
        apiKey.setExpiresAt(new Date(System.currentTimeMillis() - 1000));

        String cacheKey = "ac:key:" + apiKey.getKeyHash();
        when(valueOps.get(cacheKey)).thenReturn(null);
        doReturn(apiKey).when(apiKeyService).getOne(any(), anyBoolean());

        assertNull(apiKeyService.verifyKey("ac_bravo_abcdefgh"));
    }

    @Test
    @DisplayName("verifyKey：空 key 返回 null")
    void testVerifyKeyBlank() {
        assertNull(apiKeyService.verifyKey(null));
        assertNull(apiKeyService.verifyKey("  "));
    }

    /* ==================== M5.4：updateKey（PUT /keys/{id}） ==================== */

    @Test
    @DisplayName("updateKey：修改成功——scopes 整体覆盖 + expiresAt 覆盖 + updateTime 刷新")
    void testUpdateKeySuccess() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(100L);
        apiKey.setAgentId(1L);
        apiKey.setScopes("[\"notes:write\"]");
        apiKey.setStatus(1);
        doReturn(apiKey).when(apiKeyService).getById(100L);
        doReturn(true).when(apiKeyService).updateById(any(ApiKey.class));

        KeyUpdateReq req = new KeyUpdateReq();
        req.setScopes(List.of("notes:write", "ledger:write"));
        Date newExp = new Date(System.currentTimeMillis() + 86400000L);
        req.setExpiresAt(newExp);
        apiKeyService.updateKey(100L, req);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyService).updateById(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals("[\"notes:write\",\"ledger:write\"]", saved.getScopes());
        assertEquals(newExp, saved.getExpiresAt());
        assertNotNull(saved.getUpdateTime());
    }

    @Test
    @DisplayName("updateKey：仅改 scopes（expiresAt null=不修改）——原过期时间保留")
    void testUpdateKeyScopesOnly() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(100L);
        apiKey.setAgentId(1L);
        apiKey.setScopes("[\"notes:write\"]");
        apiKey.setStatus(1);
        Date oldExp = new Date(System.currentTimeMillis() + 86400000L);
        apiKey.setExpiresAt(oldExp);
        doReturn(apiKey).when(apiKeyService).getById(100L);
        doReturn(true).when(apiKeyService).updateById(any(ApiKey.class));

        KeyUpdateReq req = new KeyUpdateReq();
        req.setScopes(List.of("audit:read"));
        apiKeyService.updateKey(100L, req);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyService).updateById(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals("[\"audit:read\"]", saved.getScopes());
        assertEquals(oldExp, saved.getExpiresAt(), "expiresAt null 应保留原值");
    }

    @Test
    @DisplayName("updateKey：已吊销 key（status=0）拒绝，抛 400 且不落库")
    void testUpdateKeyRevoked() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(100L);
        apiKey.setStatus(0);
        doReturn(apiKey).when(apiKeyService).getById(100L);

        KeyUpdateReq req = new KeyUpdateReq();
        req.setScopes(List.of("notes:write"));
        try {
            apiKeyService.updateKey(100L, req);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (BadRequestException e) {
            assertTrue(e.getMessage().contains("已吊销"));
        }
        verify(apiKeyService, never()).updateById(any(ApiKey.class));
    }

    @Test
    @DisplayName("updateKey：空 body（两项都 null / scopes 空列表）→ 400 至少修改一项")
    void testUpdateKeyEmptyBody() {
        KeyUpdateReq req = new KeyUpdateReq();
        try {
            apiKeyService.updateKey(100L, req);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (BadRequestException e) {
            assertTrue(e.getMessage().contains("至少修改一项"));
        }
        // scopes 空列表 + expiresAt null 同样视为未修改任何项
        req.setScopes(List.of());
        try {
            apiKeyService.updateKey(100L, req);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (BadRequestException e) {
            assertTrue(e.getMessage().contains("至少修改一项"));
        }
        // 校验前置：不应触达 DB
        verify(apiKeyService, never()).getById(any());
    }

    @Test
    @DisplayName("updateKey：key 不存在 → 404")
    void testUpdateKeyNotFound() {
        doReturn(null).when(apiKeyService).getById(999L);

        KeyUpdateReq req = new KeyUpdateReq();
        req.setScopes(List.of("notes:write"));
        try {
            apiKeyService.updateKey(999L, req);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (NotFoundException e) {
            assertTrue(e.getMessage().contains("不存在"));
        }
    }

    /* ==================== M5.4-R1：deleteKey（DELETE /keys/{id}，硬删除） ==================== */

    @Test
    @DisplayName("deleteKey：删除成功——removeById 物理删除被调用")
    void testDeleteKeySuccess() {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(100L);
        apiKey.setAgentId(1L);
        apiKey.setKeyPrefix("ac_bravo_ab12");
        doReturn(apiKey).when(apiKeyService).getById(100L);
        doReturn(true).when(apiKeyService).removeById(100L);

        apiKeyService.deleteKey(100L);

        verify(apiKeyService).removeById(100L);
    }

    @Test
    @DisplayName("deleteKey：key 不存在（含重复删除）→ 抛「API Key 不存在」且不触达删除")
    void testDeleteKeyNotFound() {
        doReturn(null).when(apiKeyService).getById(999L);

        try {
            apiKeyService.deleteKey(999L);
            org.junit.jupiter.api.Assertions.fail("应抛异常");
        } catch (com.youlxb2008.admin.exceptions.BusinessException e) {
            assertTrue(e.getMessage().contains("API Key 不存在"));
        }
        verify(apiKeyService, never()).removeById(any(java.io.Serializable.class));
    }
}
