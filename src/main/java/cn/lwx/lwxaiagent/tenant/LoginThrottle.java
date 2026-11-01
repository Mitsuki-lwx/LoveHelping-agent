package cn.lwx.lwxaiagent.tenant;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
 * <h2>三态（change {@code login-throttle-shadow}）</h2>
 * <ul>
 *   <li>{@code OFF}：不计数、不记录、不拦截（排障/压测）</li>
 *   <li>{@code SHADOW}：判定照跑、退避键照写，但**不拦截**——上线前用它量误伤</li>
 *   <li>{@code ENFORCE}：默认；命中即 429</li>
 * </ul>
 * <p>判定（{@link #evaluate}）与决策（{@link #check}）分离，shadow 与 enforce 走<b>同一份</b>判定，
 * 否则量出来的不是线上那套规则。</p>
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

    /** 判定结果：locked 与触发维度。shadow 与 enforce 共用它。 */
    public record Verdict(boolean locked, long retryAfterSec, String dimension) {
        static Verdict allowed() {
            return new Verdict(false, 0, null);
        }
    }

    private final StringRedisTemplate redis;
    private final LoginThrottleProperties props;
    private final MeterRegistry meters;

    public LoginThrottle(StringRedisTemplate redis, LoginThrottleProperties props, MeterRegistry meters) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
    }

    /** 启动时把实际运行形态打进日志——"以为开了 shadow" 必须是可见的。 */
    @PostConstruct
    void logEffectiveMode() {
        switch (props.getMode()) {
            case ENFORCE -> log.info("[login-throttle] mode=ENFORCE（{} 次/{}s 起退避，IP {} 次）",
                    props.getMaxAttempts(), props.getWindowSeconds(), props.getMaxIpAttempts());
            case SHADOW -> log.warn("[login-throttle] mode=SHADOW —— 只判定不拦截；退避键照写，"
                    + "切 ENFORCE 时影子期攒下的失败会计入真实锁定");
            case OFF -> log.warn("[login-throttle] mode=OFF —— 不做失败计数，撞库零成本");
        }
    }

    /**
     * 纯判定：是否应被退避。不抛异常、不改变响应，供 shadow 与 enforce 共用。
     */
    public Verdict evaluate(String username, String ip) {
        if (props.getMode() == LoginThrottleProperties.Mode.OFF) {
            return Verdict.allowed();
        }
        try {
            Long userRetry = redis.getExpire(lockKey(username));
            if (userRetry != null && userRetry > 0) {
                return new Verdict(true, userRetry, "user");
            }
            Long ipRetry = redis.getExpire(ipLockKey(ip));
            if (ipRetry != null && ipRetry > 0) {
                return new Verdict(true, ipRetry, "ip");
            }
            return Verdict.allowed();
        } catch (RuntimeException e) {
            degraded("evaluate", e);
            return Verdict.allowed();
        }
    }

    /**
     * 登录前检查：ENFORCE 下处于退避期时抛 429；SHADOW 下只记录；OFF 直接放行。
     *
     * @throws BizException code=429，data 带 retryAfterSec
     */
    public void check(String username, String ip) {
        Verdict verdict = evaluate(username, ip);
        if (!verdict.locked()) {
            return;
        }
        if (props.getMode() == LoginThrottleProperties.Mode.SHADOW) {
            shadowRecord(verdict, username, ip);
            return;
        }
        meters.counter("auth.throttle.rejected").increment();
        throw locked(verdict.retryAfterSec());
    }

    /** shadow：只记录"本来会被拦"，指标 + 一行固定格式日志（账号只打哈希）。 */
    private void shadowRecord(Verdict verdict, String username, String ip) {
        meters.counter("auth.throttle.shadow_block", "dimension", verdict.dimension()).increment();
        log.warn("[login-throttle] would_block dimension={} user_hash={} ip={} retryAfterSec={}",
                verdict.dimension(), AiTelemetry.pseudonym(username), ip, verdict.retryAfterSec());
    }

    /** 登录失败后计数；达到阈值则开启退避。 */
    public void onFailure(String username, String ip) {
        if (props.getMode() == LoginThrottleProperties.Mode.OFF) {
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
        if (props.getMode() == LoginThrottleProperties.Mode.OFF) {
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
