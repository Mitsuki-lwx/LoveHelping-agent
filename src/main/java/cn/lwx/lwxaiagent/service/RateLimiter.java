package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Atomic user rate admission. Redis failure is bounded local degradation, never unlimited fail-open. */
@Service
public class RateLimiter {
    @SuppressWarnings("rawtypes")
    static final DefaultRedisScript<List> ACQUIRE = new DefaultRedisScript<>("""
            local quota = tonumber(ARGV[1])
            local dailyTtl = tonumber(ARGV[2])
            local capacity = tonumber(ARGV[3])
            local refill = tonumber(ARGV[4])
            local nowParts = redis.call('TIME')
            local now = tonumber(nowParts[1])*1000 + tonumber(nowParts[2])/1000
            local used = tonumber(redis.call('GET', KEYS[1]) or '0')
            if quota > 0 and used >= quota then return {2, dailyTtl} end
            local tokens = capacity
            if capacity > 0 then
                local bucket = redis.call('HMGET', KEYS[2], 'tokens', 'updated')
                if bucket[1] then
                    tokens = math.min(capacity, tonumber(bucket[1]) + math.max(0, now-tonumber(bucket[2]))*refill/1000)
                end
                if tokens < 1 then return {0, math.ceil((1-tokens)*1000/refill)} end
            end
            if quota > 0 then
                redis.call('INCR', KEYS[1])
                if redis.call('PTTL', KEYS[1]) < 0 then redis.call('PEXPIRE', KEYS[1], dailyTtl) end
            end
            if capacity > 0 then
                redis.call('HSET', KEYS[2], 'tokens', tokens-1, 'updated', now)
                redis.call('PEXPIRE', KEYS[2], math.ceil(capacity*1000/refill)+1000)
            end
            return {1, 0}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final MeterRegistry meters;
    private final AtomicLong redisUnavailableUntil = new AtomicLong();
    private final LinkedHashMap<String, Bucket> local = new LinkedHashMap<>();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties props, MeterRegistry meters) {
        this.redis = redis;
        this.props = props;
        this.meters = meters;
    }

    /** Checks AND consumes once, before execution. The old check/increment race no longer exists. */
    public void acquire(String userId) {
        if (!props.isEnabled() && !props.isBurstEnabled()) return;
        String principal = AiTelemetry.pseudonym(userId);
        if (System.nanoTime() < redisUnavailableUntil.get()) { localFallback(principal); return; }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        long ttl = Math.max(1, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE)).toMillis());
        String prefix = "rate:user:{" + principal + "}:"; // both keys share a Redis Cluster hash slot
        List<?> result;
        try {
            result = redis.execute(ACQUIRE, List.of(prefix + now.toLocalDate(), prefix + "burst"),
                    String.valueOf(props.isEnabled() ? props.getDailyQuota() : 0), String.valueOf(ttl),
                    String.valueOf(props.isBurstEnabled() ? props.getBurstCapacity() : 0), String.valueOf(props.getRefillPerSecond()));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number) || !(result.get(1) instanceof Number))
                throw new IllegalStateException("Invalid rate-limit script result");
        } catch (RuntimeException error) {
            redisUnavailableUntil.set(System.nanoTime() + props.getRedisCooldownMs() * 1_000_000L);
            metric("redis_unavailable");
            localFallback(principal);
            return;
        }
        int code = ((Number) result.get(0)).intValue();
        long wait = ((Number) result.get(1)).longValue();
        if (code != 1) throw denied(code == 2 ? "daily" : "burst", wait);
        metric("allowed");
    }

    private synchronized void localFallback(String user) {
        // A daily paid/free quota cannot be reconstructed per instance: fail closed when it is enabled.
        if (props.isEnabled()) throw new BizException(503, "配额服务暂不可用，请稍后再试", Map.of("retryAfterSec", 5));
        if (!props.isBurstEnabled()) return;
        long now = System.nanoTime();
        if (!local.containsKey(user) && local.size() >= props.getMaxLocalBuckets()) {
            long idle = (long) ((props.getBurstCapacity() / props.getRefillPerSecond() + 1) * 1_000_000_000L);
            local.entrySet().removeIf(e -> now - e.getValue().updated > idle);
            if (local.size() >= props.getMaxLocalBuckets()) throw denied("local_capacity", 5000);
        }
        Bucket bucket = local.computeIfAbsent(user, ignored -> new Bucket(props.getBurstCapacity(), now));
        bucket.tokens = Math.min(props.getBurstCapacity(), bucket.tokens + (now - bucket.updated) / 1_000_000_000.0 * props.getRefillPerSecond());
        bucket.updated = now;
        if (bucket.tokens < 1) throw denied("local_burst", (long) Math.ceil((1-bucket.tokens) * 1000 / props.getRefillPerSecond()));
        bucket.tokens--;
        metric("local_allowed");
    }
    private BizException denied(String reason, long waitMs) {
        metric(reason + "_rejected");
        return new BizException(429, "daily".equals(reason) ? "今日调用次数已用完，请明日再试" : "请求过于频繁，请稍后再试",
                Map.of("retryAfterSec", Math.max(1, (long) Math.ceil(waitMs / 1000.0)), "reason", reason));
    }
    private void metric(String outcome) { try { meters.counter("rate.limit", "outcome", outcome).increment(); } catch (RuntimeException ignored) {} }
    private static final class Bucket {
        double tokens;
        long updated;
        Bucket(double tokens, long updated) { this.tokens = tokens; this.updated = updated; }
    }
}
