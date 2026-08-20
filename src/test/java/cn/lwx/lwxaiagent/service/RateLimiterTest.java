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

/**
 * <h1>RateLimiter（速率限制器）单元测试</h1>
 *
 * <p>测试基于 Redis 的每日 API 调用限流逻辑，使用 Mockito 模拟 Redis 操作。</p>
 *
 * <p><b>限流策略：</b>每个租户每天有固定配额（默认 5 次），
 * 超过配额后抛出 BizException(429)。禁用时限流不生效。</p>
 *
 * <p><b>测试覆盖：</b></p>
 * <ul>
 *   <li>限流禁用时：不检查 Redis，不抛异常</li>
 *   <li>配额未用完：通过 checkQuota</li>
 *   <li>配额已用完：抛出 BizException(429)</li>
 *   <li>首次调用：设置过期时间（当天结束时重置）</li>
 *   <li>无历史计数：视为配额未用</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterTest {

    /** Mock Redis 字符串模板（模拟 Redis 交互） */
    @Mock
    private StringRedisTemplate redis;

    /** Mock Redis 的 ValueOperations（模拟 GET/SET/INCR 等命令） */
    @Mock
    private ValueOperations<String, String> valueOps;

    /**
     * 测试限流禁用时：不做任何检查，直接放行
     */
    @Test
    void disabled_shouldPass() {
        RateLimiter limiter = new RateLimiter(redis, false, 5);  // enabled=false
        assertDoesNotThrow(() -> limiter.checkQuota("any"));     // 不抛异常
        limiter.increment("any");                                 // 调用 increment 也不报错
        verifyNoInteractions(redis);                              // 关键：完全不和 Redis 交互
    }

    /**
     * 测试限流启用 + 配额未用完：应该通过检查
     *
     * <p>模拟 Redis 返回当前计数为 3（配额为 5，3 < 5，允许）</p>
     */
    @Test
    void enabled_withinQuota_shouldPass() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3");         // Redis 中存的是字符串 "3"
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        assertDoesNotThrow(() -> limiter.checkQuota("tenant1")); // 3 < 5，不抛异常
    }

    /**
     * 测试限流启用 + 配额已用完：应抛出 BizException(429)
     *
     * <p>429 是 HTTP 标准状态码，表示 "Too Many Requests"（请求过多）</p>
     */
    @Test
    void enabled_quotaExceeded_shouldThrow() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("5");         // 当前计数等于配额
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        BizException ex = assertThrows(BizException.class, () -> limiter.checkQuota("tenant1"));
        assertEquals(429, ex.getCode());                          // 状态码为 429
    }

    /**
     * 测试首次 increment：计数变为 1 时，应设置过期时间
     *
     * <p>过期时间计算为当天结束时（每天配额自动重置）。</p>
     */
    @Test
    void enabled_firstIncrement_setsExpiry() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);     // Redis INCR 返回 1
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        limiter.increment("tenant1");
        verify(valueOps).increment(anyString());                  // 验证调用了 INCR
        verify(redis).expire(anyString(), any(Duration.class));   // 验证设置了过期时间
    }

    /**
     * 测试无历史计数时：视为配额未用，允许通过
     *
     * <p>Redis GET 返回 null 说明该租户没有调用记录，配额未使用</p>
     */
    @Test
    void enabled_noExistingCount_shouldPass() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);         // 没有历史记录
        RateLimiter limiter = new RateLimiter(redis, true, 5);
        assertDoesNotThrow(() -> limiter.checkQuota("new-tenant"));
    }
}
