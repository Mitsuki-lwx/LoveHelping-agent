package cn.lwx.lwxaiagent.rag.rerank;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.rag.rerank")
public class RerankProperties {
    private boolean enabled;
    @Min(1) @Max(50) private int topN = 20;
    @Min(1) @Max(50) private int topK = 5;
    @Pattern(regexp = "local|llm|off") private String mode = "local";
    @Min(1) @Max(1200) private int maxCandidateChars = 1000;
    private String url = "http://127.0.0.1:8091/rerank";
    @Min(1) @Max(10000) private long timeoutMs = 2000;
    @Min(1) @Max(10000) private long connectTimeoutMs = 500;
    @Min(1) @Max(16) private int maxConcurrent = 2;
    @Min(1) private int failureThreshold = 3;
    @Min(1) private long circuitOpenMs = 15000;
    @AssertTrue(message = "rerank top-k must not exceed top-n")
    public boolean isWindowValid() { return topK <= topN; }
    public boolean isActive() { return enabled && !"off".equals(mode); }
}
