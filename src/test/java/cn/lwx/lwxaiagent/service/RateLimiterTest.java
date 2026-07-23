package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Test
    void disabled_shouldPass() {
        RateLimiter limiter = new RateLimiter(redis, false, 5);
        assertDoesNotThrow(() -> limiter.checkQuota("any"));
        limiter.increment("any");
        verifyNoInteractions(redis);
    }

    @Test
    void enabled_withinQuota_shouldPass() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        assertDoesNotThrow(() -> limiter.checkQuota("tenant1"));
    }

    @Test
    void enabled_quotaExceeded_shouldThrow() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("5");
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        BizException ex = assertThrows(BizException.class, () -> limiter.checkQuota("tenant1"));
        assertEquals(429, ex.getCode());
    }

    @Test
    void enabled_firstIncrement_setsExpiry() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        limiter.increment("tenant1");
        verify(valueOps).increment(anyString());
        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    void enabled_noExistingCount_shouldPass() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        assertDoesNotThrow(() -> limiter.checkQuota("new-tenant"));
    }
}
