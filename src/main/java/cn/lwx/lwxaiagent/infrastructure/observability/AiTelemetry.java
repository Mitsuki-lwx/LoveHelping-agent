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

    public static String pseudonym(String raw) {
        if (raw == null || raw.isBlank()) return "anonymous";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
