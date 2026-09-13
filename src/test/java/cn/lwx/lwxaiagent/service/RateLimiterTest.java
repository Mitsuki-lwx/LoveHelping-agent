package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterTest {
    @Test void disabledNeverTouchesRedis() {
        var redis = mock(StringRedisTemplate.class); var p = new RateLimitProperties(); p.setBurstEnabled(false);
        new RateLimiter(redis, p, new SimpleMeterRegistry()).acquire("user"); verifyNoInteractions(redis);
    }
    @Test void atomicScriptAllows() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L));
        new RateLimiter(redis, new RateLimitProperties(), new SimpleMeterRegistry()).acquire("user");
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(redis, never()).opsForValue();
    }
    @Test void quotaRejectionIncludesRetryHint() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(2L, 3000L));
        var p = new RateLimitProperties(); p.setEnabled(true);
        var ex = assertThrows(BizException.class, () -> new RateLimiter(redis, p, new SimpleMeterRegistry()).acquire("user"));
        assertEquals(429, ex.getCode()); assertNotNull(ex.getData());
    }
    @Test void localFallbackIsBoundedAndCooldownAvoidsRedisStorm() throws Exception {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new IllegalStateException("offline"));
        var p = new RateLimitProperties(); p.setBurstCapacity(4); p.setRefillPerSecond(0.01);
        var limiter = new RateLimiter(redis, p, new SimpleMeterRegistry());
        limiter.acquire("same-user");
        AtomicInteger allowed = new AtomicInteger(1);
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<Void>> work = java.util.stream.IntStream.range(0, 100).mapToObj(i -> (Callable<Void>) () -> {
                try { limiter.acquire("same-user"); allowed.incrementAndGet(); } catch (BizException expected) { assertEquals(429, expected.getCode()); }
                return null;
            }).toList();
            for (Future<Void> f : executor.invokeAll(work)) f.get();
        }
        assertEquals(4, allowed.get()); verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
    @Test void localFallbackCannotEvictActiveUsersToResetQuota() {
        var redis = mock(StringRedisTemplate.class);
        var p = new RateLimitProperties(); p.setMaxLocalBuckets(1); p.setBurstCapacity(1); p.setRefillPerSecond(0.01);
        var limiter = new RateLimiter(redis, p, new SimpleMeterRegistry());
        limiter.acquire("first");
        assertThrows(BizException.class, () -> limiter.acquire("second"));
        assertThrows(BizException.class, () -> limiter.acquire("first"));
    }
    @Test void dailyQuotaFailsClosedOnRedisFailure() {
        var redis = mock(StringRedisTemplate.class); var p = new RateLimitProperties(); p.setEnabled(true);
        BizException e = assertThrows(BizException.class, () -> new RateLimiter(redis, p, new SimpleMeterRegistry()).acquire("user"));
        assertEquals(503, e.getCode());
    }
}
