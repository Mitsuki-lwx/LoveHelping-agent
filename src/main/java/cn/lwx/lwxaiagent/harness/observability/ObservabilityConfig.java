package cn.lwx.lwxaiagent.harness.observability;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Observability configuration class.
 * Checks OTLP (OpenTelemetry Protocol) export endpoint configuration status at application startup.
 * <p>
 * How it works: Spring Boot Actuator + Micrometer Tracing auto-detects opentelemetry-exporter-otlp on the classpath,
 * and automatically creates an OTLP exporter. This class only performs configuration validation and startup logging.
 * <p>
 * Configuration (3 environment variables):
 * OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.langsmith.ai/v1/traces
 * OTEL_EXPORTER_OTLP_HEADERS=x-api-key=${LANGSMITH_API_KEY}
 * OTEL_SERVICE_NAME=lwx-ai-agent
 */
@Slf4j
@Configuration
public class ObservabilityConfig {

    /** OTLP export endpoint address, injected from application-local.yml */
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    /** Check OTLP configuration readiness at startup */
    @PostConstruct
    public void init() {
        if (otlpEndpoint.isBlank()) {
            log.warn("OTLP endpoint not configured — no traces exported.");
            log.warn("Set management.otlp.tracing.endpoint in application-local.yml");
        } else {
            log.info("OpenTelemetry tracing enabled → exporting to {}", otlpEndpoint);
        }
    }
}
