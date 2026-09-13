package cn.lwx.lwxaiagent.rag.rerank;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.TraceContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** All providers share one fallback boundary and an observable top-K bound. */
@Component
public class RerankDocumentPostProcessor implements DocumentPostProcessor {
    private final RerankProperties properties;
    private final LlmDocumentReranker llm;
    private final LocalDocumentReranker local;
    private final MeterRegistry meters;
    private final AiTelemetry telemetry;

    public RerankDocumentPostProcessor(RerankProperties properties, LlmDocumentReranker llm,
                                       LocalDocumentReranker local, MeterRegistry meters, AiTelemetry telemetry) {
        this.properties = properties; this.llm = llm; this.local = local; this.meters = meters; this.telemetry = telemetry;
    }

    @Override public List<Document> process(Query query, List<Document> documents) {
        if (!properties.isActive() || documents == null || documents.size() <= properties.getTopK()) return documents;
        int k = Math.min(properties.getTopK(), documents.size());
        Object parent = query.context().get(AiTelemetry.PARENT_CONTEXT_KEY);
        var span = telemetry.start("rag.rerank", telemetry.capture() != null ? telemetry.capture() : parent instanceof TraceContext t ? t : null);
        span.tag("rag.mode", properties.getMode()).tag("rag.candidates", String.valueOf(documents.size()));
        long start = System.nanoTime();
        String outcome = "success";
        try (var ignored = telemetry.scope(span)) {
            meters.counter("rag.rerank.executions", "mode", properties.getMode()).increment();
            List<Document> ranked = ("local".equals(properties.getMode()) ? local : llm)
                    .rerank(query.text(), documents.subList(0, Math.min(documents.size(), properties.getTopN())), k);
            if (ranked == null || ranked.size() != k) throw new IllegalStateException("Invalid rerank result");
            return ranked;
        } catch (java.util.concurrent.CancellationException cancelled) {
            outcome = "cancelled"; throw cancelled;
        } catch (RuntimeException e) {
            outcome = "fallback";
            meters.counter("rag.rerank.fallback", "mode", properties.getMode()).increment();
            span.tag("langfuse.observation.level", "WARNING");
            return List.copyOf(documents.subList(0, k));
        } finally {
            span.tag("rag.outcome", outcome).tag("rag.results", String.valueOf(k));
            meters.timer("rag.rerank.latency", "mode", properties.getMode(), "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            span.end();
        }
    }
}
