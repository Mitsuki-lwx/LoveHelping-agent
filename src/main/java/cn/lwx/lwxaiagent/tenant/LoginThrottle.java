package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * <h1>登录失败节流</h1>
 *
 * <p>change {@code security-hardening}（2026-11-01）之前，{@code /auth/login} 没有任何失败计数，
 * 配合"限流默认关闭"，口令爆破与横向撞库是零成本的。这里补上两个维度的计数与指数退避。</p>
 *
 * <h2>为什么是退避而不是硬锁</h2>
 * <p>硬锁账号可以被攻击者用来 DoS 合法用户。退避把攻击者的成本拉高，同时把受害者的最长等待压在
 * {@code maxBackoffSeconds}（默认 15 分钟），且不返回"账号是否存在"这一位信息。</p>
 *
 * <h2>Redis 不可用时</h2>
 * <p><b>fail-open</b>：登录不能被缓存故障拖成全站不可用。降级时打 WARN 并记
 * {@code auth.throttle.degraded} 指标——宁可短暂失去节流，也不能让所有人登不进来。</p>
 */
@Slf4j
@Component
public class LoginThrottle {

    /** 统一文案：不区分"账号不存在/密码错误/被锁"，避免枚举。 */
    static final String LOCKED_MESSAGE = "登录尝试过于频繁，请稍后再试";

    private final StringRedisTemplate redis;
    private final LoginThrottleProperties props;
    private final MeterRegistry meters;

    public LoginThrottle(StringRedisTemplate redis, LoginThrottleProperties props, MeterRegistry meters) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
    }

    /**
     * 登录前检查：账号或 IP 处于退避期时抛 429。
     *
     * @throws BizException code=429，data 带 retryAfterSec
     */
    public void check(String username, String ip) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            Long userRetry = redis.getExpire(lockKey(username));
            if (userRetry != null && userRetry > 0) {
                throw locked(userRetry);
            }
            Long ipRetry = redis.getExpire(ipLockKey(ip));
            if (ipRetry != null && ipRetry > 0) {
                throw locked(ipRetry);
            }
        } catch (BizException e) {
            meters.counter("auth.throttle.rejected").increment();
            throw e;
        } catch (RuntimeException e) {
            degraded("check", e);
        }
    }

    /** 登录失败后计数；达到阈值则开启退避。 */
    public void onFailure(String username, String ip) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            long userFailures = increment(failKey(username));
            long ipFailures = increment(ipFailKey(ip));
            if (userFailures >= props.getMaxAttempts()) {
                applyBackoff(lockKey(username), userFailures - props.getMaxAttempts());
            }
            if (ipFailures >= props.getMaxIpAttempts()) {
                applyBackoff(ipLockKey(ip), ipFailures - props.getMaxIpAttempts());
            }
            meters.counter("auth.throttle.failure").increment();
        } catch (RuntimeException e) {
            degraded("onFailure", e);
        }
    }

    /** 登录成功后清掉该账号的失败计数（IP 计数保留，横向撞库不会因为一次成功被清空）。 */
    public void onSuccess(String username) {
        if (!props.isEnabled()) {
            return;
        }
        try {
            redis.delete(failKey(username));
            redis.delete(lockKey(username));
        } catch (RuntimeException e) {
            degraded("onSuccess", e);
        }
    }

    private long increment(String key) {
        Long value = redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            redis.expire(key, Duration.ofSeconds(props.getWindowSeconds()));
        }
        return value == null ? 0 : value;
    }

    /** 指数退避：base * 2^excess，截断到 maxBackoffSeconds。 */
    private void applyBackoff(String lockKey, long excess) {
        long seconds = Math.min((long) props.getMaxBackoffSeconds(),
                (long) props.getBaseBackoffSeconds() << Math.min(10, Math.max(0, excess)));
        redis.opsForValue().set(lockKey, "1", Duration.ofSeconds(seconds));
    }

    private BizException locked(long retryAfterSec) {
        return new BizException(429, LOCKED_MESSAGE, Map.of("retryAfterSec", retryAfterSec));
    }

    private void degraded(String where, RuntimeException e) {
        meters.counter("auth.throttle.degraded").increment();
        log.warn("[login-throttle] Redis 不可用，本次降级为放行（{}）：{}", where, e.toString());
    }

    private static String failKey(String username) {
        return "auth:fail:user:" + username;
    }

    private static String lockKey(String username) {
        return "auth:lock:user:" + username;
    }

    private static String ipFailKey(String ip) {
        return "auth:fail:ip:" + ip;
    }

    private static String ipLockKey(String ip) {
        return "auth:lock:ip:" + ip;
    }
}
