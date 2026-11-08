package com.youlxb2008.admin.modules.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AuditService 单元测试
 * 覆盖：append 写 Redis List 缓冲、flush 批量落库 + LTRIM、Redis 不可用降级直接落库
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    @InjectMocks
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    @Test
    @DisplayName("append：写 Redis List ac:audit:buffer")
    void testAppendToBuffer() {
        auditService.append("bravo", "note.submit", "10", "{\"method\":\"POST\"}", "success", "127.0.0.1");
        verify(listOps).rightPush(eq("ac:audit:buffer"), anyString());
        // 写缓冲成功不应直接落库
        verify(auditService, never()).save(any());
    }

    @Test
    @DisplayName("append：Redis 不可用（null）时降级直接落库")
    void testAppendFallbackDirectDb() {
        AuditService noRedis = org.mockito.Mockito.spy(new AuditService());
        org.springframework.test.util.ReflectionTestUtils.setField(noRedis, "objectMapper", new ObjectMapper());
        doReturn(true).when(noRedis).save(any());
        noRedis.append("bravo", "note.submit", "10", null, "success", "127.0.0.1");
        verify(noRedis).save(any());
    }

    @Test
    @DisplayName("flush：批量落库并 LTRIM 已处理部分")
    void testFlush() {
        String json = "{\"principal\":\"bravo\",\"action\":\"note.submit\",\"resource\":\"10\","
                + "\"request\":null,\"result\":\"success\",\"clientIp\":\"127.0.0.1\"}";
        when(listOps.range("ac:audit:buffer", 0, 99)).thenReturn(List.of(json, json));
        doReturn(true).when(auditService).saveBatch(anyCollection());

        int flushed = auditService.flush();

        assertEquals(2, flushed);
        verify(auditService).saveBatch(anyCollection());
        verify(listOps).trim("ac:audit:buffer", 2, -1);
    }

    @Test
    @DisplayName("flush：缓冲为空返回 0，不落库")
    void testFlushEmpty() {
        when(listOps.range("ac:audit:buffer", 0, 99)).thenReturn(List.of());
        assertEquals(0, auditService.flush());
        verify(auditService, never()).saveBatch(anyCollection());
    }

    @Test
    @DisplayName("flush：Redis 不可用（null）返回 0")
    void testFlushNoRedis() {
        AuditService noRedis = new AuditService();
        org.springframework.test.util.ReflectionTestUtils.setField(noRedis, "objectMapper", new ObjectMapper());
        assertEquals(0, noRedis.flush());
    }

    @Test
    @DisplayName("flush：JSON 全部解析失败时清空该批，避免死循环")
    void testFlushAllParseFail() {
        when(listOps.range("ac:audit:buffer", 0, 99)).thenReturn(List.of("not-json", "not-json"));
        assertEquals(0, auditService.flush());
        verify(listOps).trim("ac:audit:buffer", 2, -1);
        verify(auditService, never()).saveBatch(anyCollection());
    }
}
