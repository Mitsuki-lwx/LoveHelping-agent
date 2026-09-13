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
    /** Legacy daily quota remains opt-in; abuse protection has its own switch. */
    private boolean enabled;
    @Min(1) private int dailyQuota = 5;
    private boolean burstEnabled = true;
    @Min(1) @Max(10000) private int burstCapacity = 8;
    @DecimalMin("0.01") @DecimalMax("10000.0") private double refillPerSecond = 1;
    @Min(1) @Max(100000) private int maxLocalBuckets = 10000;
    @Min(1) private long redisCooldownMs = 5000;
}
