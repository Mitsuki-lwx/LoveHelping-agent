package cn.lwx.lwxaiagent.infrastructure.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.*;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.boot.actuate.autoconfigure.tracing.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** ADR-24: one bounded OTLP export path. SDK provider shutdown flushes and closes the processor. */
@Configuration
public class LangfuseTracingConfig {
    @Bean
    @ConditionalOnProperty(prefix = "app.langfuse", name = "enabled", havingValue = "true")
    SdkTracerProviderBuilderCustomizer langfuseExporter(LangfuseProperties p) {
        if (p.getPublicKey() == null || p.getPublicKey().isBlank() || p.getSecretKey() == null || p.getSecretKey().isBlank())
            throw new IllegalArgumentException("Langfuse enabled but LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY missing");
        URI host = URI.create(p.getHost());
        if (!Set.of("http", "https").contains(host.getScheme()) || host.getHost() == null
                || host.getUserInfo() != null || host.getQuery() != null || host.getFragment() != null)
            throw new IllegalArgumentException("Invalid Langfuse host");
        if (p.getMaxBatchSize() > p.getMaxQueueSize()) throw new IllegalArgumentException("Langfuse batch exceeds queue capacity");
        String endpoint = p.getHost().replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        String credentials = Base64.getEncoder().encodeToString((p.getPublicKey() + ":" + p.getSecretKey()).getBytes(StandardCharsets.UTF_8));
        return builder -> {
            SpanExporter exporter = new SafeExporter(OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint).addHeader("Authorization", "Basic " + credentials)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofMillis(p.getTimeoutMs())).build());
            builder.addSpanProcessor(new SessionProcessor());
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                    .setMaxQueueSize(p.getMaxQueueSize()).setMaxExportBatchSize(p.getMaxBatchSize())
                    .setScheduleDelay(Duration.ofMillis(p.getScheduleDelayMs()))
                    .setExporterTimeout(Duration.ofMillis(p.getTimeoutMs())).build());
        };
    }

    /** Copies non-sensitive session metadata without network baggage propagation. */
    static final class SessionProcessor implements SpanProcessor {
        private static final List<String> KEYS = List.of("langfuse.session.id", "langfuse.user.id", "langfuse.trace.name");
        @Override public void onStart(Context parent, ReadWriteSpan span) {
            if (Span.fromContext(parent) instanceof ReadableSpan p) {
                for (String key : KEYS) {
                    var attribute = AttributeKey.stringKey(key);
                    String value = p.getAttribute(attribute);
                    if (value != null) span.setAttribute(attribute, value);
                }
            }
        }
        @Override public boolean isStartRequired() { return true; }
        @Override public void onEnd(ReadableSpan span) {}
        @Override public boolean isEndRequired() { return false; }
    }

    /** Deny-by-default export boundary also scrubs auto-instrumented URLs, headers and exception events. */
    static final class SafeExporter implements SpanExporter {
        private final SpanExporter delegate;
        SafeExporter(SpanExporter delegate) { this.delegate = delegate; }
        private static final Set<String> EXACT = Set.of(
                "langfuse.session.id", "langfuse.user.id", "langfuse.trace.name", "langfuse.observation.type",
                "langfuse.observation.model.name", "langfuse.observation.usage_details", "langfuse.observation.level",
                "http.request.method", "http.method", "http.response.status_code", "http.status_code", "http.route",
                "gen_ai.request.model", "gen_ai.response.model", "gen_ai.operation.name", "gen_ai.system",
                "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.prompt_tokens", "gen_ai.usage.completion_tokens",
                "llm.provider", "llm.attempt", "llm.outcome", "graph.route", "graph.node", "graph.outcome",
                "rag.candidates", "rag.results", "rag.outcome", "rag.mode", "tool.name", "tool.outcome");
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            List<SpanData> safe = spans.stream().map(s -> (SpanData) new DelegatingSpanData(s) {
                @Override public Attributes getAttributes() {
                    var out = Attributes.builder();
                    s.getAttributes().forEach((key, value) -> { if (EXACT.contains(key.getKey())) put(out, key, value); });
                    return out.build();
                }
                @Override public List<EventData> getEvents() { return List.of(); }
                @Override public StatusData getStatus() { return StatusData.create(s.getStatus().getStatusCode(), ""); }
            }).toList();
            return delegate.export(safe);
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void put(io.opentelemetry.api.common.AttributesBuilder out, AttributeKey key, Object value) { out.put(key, value); }
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
