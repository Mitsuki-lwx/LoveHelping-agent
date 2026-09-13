package cn.lwx.lwxaiagent.infrastructure.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Single retry owner and bounded failure budgets (ADR-23). All durations are milliseconds. */
@Data
@Validated
@ConfigurationProperties(prefix = "app.llm")
public class LlmGatewayProperties {
    @Valid private Retry retry = new Retry();
    @Valid private Circuit circuit = new Circuit();
    private boolean fallbackEnabled = true;
    @Min(1) @Max(256) private int maxConcurrentCalls = 24;
    @Min(1) private long attemptTimeoutMs = 25000;
    @Min(1) private long totalTimeoutMs = 60000;
    @Min(1) private long streamIdleTimeoutMs = 15000;
    @Min(1) private long connectTimeoutMs = 3000;

    @Data
    public static class Retry {
        /** Includes the initial primary attempt; fallback is attempted at most once. */
        @Min(1) @Max(5) private int maxAttempts = 3;
        @Min(0) private long backoffMs = 250;
        @Min(1) private long maxBackoffMs = 5000;
        @DecimalMin("0.0") @DecimalMax("1.0") private double jitter = 0.5;
        /** Shared extra-attempt budget per process, not a quota per caller. */
        @Min(0) @Max(10000) private int budgetPerMinute = 30;
        /** Compatibility for callers using the original boolean jitter flag. */
        public boolean isJitter() { return jitter > 0; }
    }

    @Data
    public static class Circuit {
        private boolean enabled = true;
        @Min(1) private int failureThreshold = 5;
        @Min(1) private long openMs = 15000;
    }
}
