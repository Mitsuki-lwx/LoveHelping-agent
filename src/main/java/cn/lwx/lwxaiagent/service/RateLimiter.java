package cn.lwx.lwxaiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Slf4j
@Service
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int dailyQuota;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${app.rate-limit.enabled:true}") boolean enabled,
                       @Value("${app.rate-limit.daily-quota:5}") int dailyQuota) {
        this.redis = redis;
        this.enabled = enabled;
        this.dailyQuota = dailyQuota;
    }

    public void checkQuota(String tenantId) {
        if (!enabled) return;

        String key = key(tenantId);
        String val = redis.opsForValue().get(key);
        int used = val != null ? Integer.parseInt(val) : 0;

        if (used >= dailyQuota) {
            log.info("Rate limit hit: tenant={}, used={}/{}", tenantId, used, dailyQuota);
            throw new cn.lwx.lwxaiagent.common.BizException(429,
                    String.format("今日免费次数已用完（%d/%d），开通会员可继续使用", used, dailyQuota));
        }
    }

    public void increment(String tenantId) {
        if (!enabled) return;

        String key = key(tenantId);
        Long used = redis.opsForValue().increment(key);
        if (used != null && used == 1) {
            redis.expire(key, ttlUntilEndOfDay());
        }
    }

    private String key(String tenantId) {
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return "rate:" + tenantId + ":" + date;
    }

    private static Duration ttlUntilEndOfDay() {
        return Duration.between(LocalTime.now(ZoneId.of("Asia/Shanghai")), LocalTime.MAX);
    }
}
