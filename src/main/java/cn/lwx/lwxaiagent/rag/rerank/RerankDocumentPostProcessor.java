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
            // 引擎选择（2026-09-21 修正）：`local` 与 `remote` 是**同一个 HTTP 实现**——
            // LocalDocumentReranker 已扩展出 remote 分支（model 字段 + Bearer 鉴权 + https 校验），
            // 两者只差 endpoint/鉴权；`llm` 才走主线模型打分。
            //
            // 原写法 `"local".equals(mode) ? local : llm` 会把 remote **静默路由到 LLM 重排**，
            // 后果：RERANK_MODE=remote 时硅基流动 /v1/rerank **一次都没被调用**，
            // 而配置回显、鉴权校验、单测全绿（单测只覆盖 LocalDocumentReranker 自身，
            // 接线层无人守）→ 属于"能力建好、就是没接上"，实测才发现。
            DocumentReranker engine = "llm".equals(properties.getMode()) ? llm : local;
            List<Document> ranked = engine
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
