package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 登录失败节流（change security-hardening / task 4.4）。
 *
 * <p>覆盖四条：退避期拒绝 · 阈值触发退避 · 成功清零 · Redis 故障 fail-open。</p>
 */
class LoginThrottleTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final LoginThrottleProperties props = new LoginThrottleProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private LoginThrottle throttle() {
        when(redis.opsForValue()).thenReturn(ops);
        return new LoginThrottle(redis, props, meters);
    }

    @Test
    void shadow_命中退避也不拦截但记指标与日志() {
        props.setMode(LoginThrottleProperties.Mode.SHADOW);
        when(redis.getExpire("auth:lock:user:u1")).thenReturn(90L);

        assertDoesNotThrow(() -> throttle().check("u1", "1.1.1.1"),
                "shadow 只判定不拦截，否则就不是标定而是上线");

        assertEquals(1.0, meters.counter("auth.throttle.shadow_block", "dimension", "user").count());
    }

    @Test
    void shadow_未命中退避时不产生would_block() {
        props.setMode(LoginThrottleProperties.Mode.SHADOW);
        when(redis.getExpire(anyString())).thenReturn(-1L);
        throttle().check("u1", "1.1.1.1");
        assertEquals(0.0, meters.counter("auth.throttle.shadow_block", "dimension", "user").count());
    }

    @Test
    void off_完全不碰Redis() {
        props.setMode(LoginThrottleProperties.Mode.OFF);
        LoginThrottle t = throttle();
        t.check("u1", "1.1.1.1");
        t.onFailure("u1", "1.1.1.1");
        t.onSuccess("u1");
        verifyNoInteractions(redis);
    }

    @Test
    void evaluate_是纯判定不抛异常() {
        when(redis.getExpire("auth:lock:user:u1")).thenReturn(60L);
        LoginThrottle.Verdict v = throttle().evaluate("u1", "1.1.1.1");
        assertTrue(v.locked());
        assertEquals("user", v.dimension());
        assertEquals(60L, v.retryAfterSec());
    }

    @Test
    void 处于退避期时抛429且带重试秒数() {
        when(redis.getExpire("auth:lock:user:u1")).thenReturn(120L);
        BizException e = assertThrows(BizException.class, () -> throttle().check("u1", "1.1.1.1"));
        assertEquals(429, e.getCode());
        assertEquals(120L, ((java.util.Map<?, ?>) e.getData()).get("retryAfterSec"));
    }

    @Test
    void 未锁定则放行() {
        when(redis.getExpire(anyString())).thenReturn(-1L);
        assertDoesNotThrow(() -> throttle().check("u1", "1.1.1.1"));
    }

    @Test
    void 连续失败达到阈值后写入退避键() {
        when(ops.increment("auth:fail:user:u1")).thenReturn(5L);   // maxAttempts=5 → excess=0
        when(ops.increment("auth:fail:ip:1.1.1.1")).thenReturn(5L);
        throttle().onFailure("u1", "1.1.1.1");
        verify(ops).set(eq("auth:lock:user:u1"), eq("1"), eq(Duration.ofSeconds(props.getBaseBackoffSeconds())));
    }

    @Test
    void 登录成功清掉账号计数与退避() {
        throttle().onSuccess("u1");
        verify(redis).delete("auth:fail:user:u1");
        verify(redis).delete("auth:lock:user:u1");
    }

    @Test
    void Redis故障时fail_open不阻断登录() {
        when(redis.getExpire(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrow(() -> throttle().check("u1", "1.1.1.1"),
                "缓存故障不能把登录拖成全站不可用");

        when(ops.increment(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrow(() -> throttle().onFailure("u1", "1.1.1.1"));
    }
}
