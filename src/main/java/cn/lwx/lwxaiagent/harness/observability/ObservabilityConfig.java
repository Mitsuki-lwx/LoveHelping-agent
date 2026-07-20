package cn.lwx.lwxaiagent.harness.observability;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置类。
 * 负责在应用启动时检查 OTLP（OpenTelemetry Protocol）导出端点的配置状态。
 * <p>
 * 工作原理：Spring Boot Actuator + Micrometer Tracing 自动检测到 classpath 上的
 * opentelemetry-exporter-otlp，自动创建 OTLP 导出器。这个类只做配置校验和启动日志。
 * <p>
 * 配置方式（3 行环境变量）：
 * OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp.langsmith.ai/v1/traces
 * OTEL_EXPORTER_OTLP_HEADERS=x-api-key=${LANGSMITH_API_KEY}
 * OTEL_SERVICE_NAME=lwx-ai-agent
 */
@Slf4j
@Configuration
public class ObservabilityConfig {

    /** OTLP 导出端点地址，从 application-local.yml 注入 */
    @Value("${management.otlp.tracing.endpoint:}")
    private String otlpEndpoint;

    /** 启动时检查 OTLP 配置是否就绪 */
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
