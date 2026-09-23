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
@lombok.extern.slf4j.Slf4j
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
            // 降级原因必须可分（2026-09-23 实测发现）：此前 fallback 只有一个总计数、
            // 且**一条日志都不打** → 实测出现 58 次重排中 45 次降级时，
            // "到底是并发许可不足、上游故障，还是熔断打开"从日志和指标都答不上来，
            // 只能翻源码 + 交叉 3 个计数推断。reason 取值有限（见 reasonOf），不膨胀指标基数；
            // DEBUG 一行供排查时按需打开（INFO 级别下不输出，避免高 QPS 刷屏）。
            String reason = reasonOf(e);
            meters.counter("rag.rerank.fallback", "mode", properties.getMode(), "reason", reason).increment();
            span.tag("langfuse.observation.level", "WARNING");
            // 传异常对象本身（SLF4J 约定：末位参数是 Throwable 时打完整堆栈 + cause）。
            // 只打 getMessage() 会丢 cause —— 实测出现 reason=upstream 时，
            // 外层消息恒为 "Local reranker unavailable"，看不出底层是限流、连不上还是超时。
            log.debug("Rerank fallback ({})", reason, e);
            return List.copyOf(documents.subList(0, k));
        } finally {
            span.tag("rag.outcome", outcome).tag("rag.results", String.valueOf(k));
            meters.timer("rag.rerank.latency", "mode", properties.getMode(), "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            span.end();
        }
    }

    /**
     * 把降级原因归类成有限取值，便于按原因看分布（而不是只知道"降级了 45 次"）。
     *
     * <p>判定依据是异常消息里的固定字样——这些字样由 {@link LocalDocumentReranker} 抛出，
     * 属本模块内部契约（不是外部系统的任意文案）。逐层遍历 cause，兼容未来被包装的情况。</p>
     *
     * <p>取值：{@code saturated}（并发许可不足，本地容量问题，**不代表上游有问题**）/
     * {@code circuit_open}（熔断已打开）/ {@code missing_key}（配置缺失，调用期才发现）/
     * {@code upstream}（真正打到上游但失败）/ {@code other}（未归类，出现即说明新增了抛出点）。</p>
     */
    static String reasonOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            if (m.contains("saturated")) return "saturated";
            if (m.contains("circuit open")) return "circuit_open";
            if (m.contains("SF_API_KEY")) return "missing_key";
            if (m.contains("unavailable")) return "upstream";
        }
        return "other";
    }
}
