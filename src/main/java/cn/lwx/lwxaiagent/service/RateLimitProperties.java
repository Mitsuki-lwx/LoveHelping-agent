package cn.lwx.lwxaiagent.service;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {
    /**
     * 每日配额总开关。change security-hardening（2026-11-01）起**默认开启**：
     * 此前默认关 = 刷接口/撞库零成本。生产实际值以 {@code application.yml} 为准，
     * 关闭只需环境变量 {@code DAILY_QUOTA_ENABLED=false}。
     */
    private boolean enabled = true;
    /** 单用户每日对话次数（默认 100；5 是收费档假设，不适合当默认）。 */
    @Min(1) private int dailyQuota = 100;
    private boolean burstEnabled = true;
    @Min(1) @Max(10000) private int burstCapacity = 8;
    @DecimalMin("0.01") @DecimalMax("10000.0") private double refillPerSecond = 1;
    @Min(1) @Max(100000) private int maxLocalBuckets = 10000;
    @Min(1) private long redisCooldownMs = 5000;
}
