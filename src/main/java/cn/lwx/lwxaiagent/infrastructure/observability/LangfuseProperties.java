package cn.lwx.lwxaiagent.infrastructure.observability;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Langfuse >=3.22 OTLP HTTP uses Basic(publicKey:secretKey), not custom HMAC signing. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.langfuse")
public class LangfuseProperties {
    private boolean enabled;
    private String publicKey;
    private String secretKey;
    private String host = "http://localhost:3000";
    @Min(1) @Max(65536) private int maxQueueSize = 2048;
    @Min(1) @Max(512) private int maxBatchSize = 256;
    @Min(1) private long scheduleDelayMs = 1000;
    @Min(1) private long timeoutMs = 3000;
}
