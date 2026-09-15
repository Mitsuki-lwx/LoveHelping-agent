package cn.lwx.lwxaiagent.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit tracing at async boundaries; no global Reactor hooks or private text attributes. */
@Component
public class AiTelemetry {
    public static final String PARENT_CONTEXT_KEY = AiTelemetry.class.getName() + ".parent";
    private final Tracer tracer;

    public AiTelemetry(Tracer tracer) { this.tracer = tracer; }

    public TraceContext capture() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context();
    }

    public Context propagate(Context context, TraceContext parent) {
        return parent == null ? context : context.put(PARENT_CONTEXT_KEY, parent);
    }

    public Span start(String name, TraceContext parent) {
        var builder = tracer.spanBuilder().name(name);
        if (parent != null) builder.setParent(parent);
        return builder.start();
    }

    public Tracer.SpanInScope scope(Span span) { return tracer.withSpan(span); }

    /** Record a safe error category, NEVER exception messages / HTTP response bodies / prompts. */
    public void failure(Span span, String reason) {
        span.tag("langfuse.observation.level", "ERROR");
        span.tag("langfuse.observation.status_message", reason);
        span.error(new IllegalStateException(reason));
    }

    /** Fixed taxonomy only: useful diagnostics without private response bodies or exception messages. */
    public static String failureCategory(Throwable error) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause()) {
            if (reactor.core.Exceptions.isOverflow(e)) return "backpressure";
            if (e instanceof java.util.concurrent.TimeoutException || e instanceof java.net.http.HttpTimeoutException) return "timeout";
            if (e instanceof java.util.concurrent.CancellationException || e instanceof InterruptedException) return "cancelled";
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException http) return "http_" + http.getStatusCode().value();
            if (e instanceof org.springframework.web.client.RestClientResponseException http) return "http_" + http.getStatusCode().value();
            if (e instanceof java.io.IOException) return "transport";
        }
        return "execution_failed";
    }

    public static String pseudonym(String raw) {
        if (raw == null || raw.isBlank()) return "anonymous";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
