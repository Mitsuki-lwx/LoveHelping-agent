package cn.lwx.lwxaiagent.infrastructure.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导出边界的拒绝表：Spring Security 过滤链 span 必须丢，否则每次 Prometheus 抓取都可能在
 * Langfuse 里留下一条**无名 trace**（碎片）。
 *
 * <p>本测试用真实的 {@link SdkTracerProvider} + {@link SimpleSpanProcessor} 造 span，
 * 以复现真实的**结束顺序**：根 span 最后结束，子 span 先落到导出边界。</p>
 */
class SafeExporterTest {

    /** 记录被真正导出的 span 名。 */
    static final class Capturing implements SpanExporter {
        final List<String> names = Collections.synchronizedList(new ArrayList<>());

        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            spans.forEach(s -> names.add(s.getName()));
            return CompletableResultCode.ofSuccess();
        }

        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    /** 跑一段场景，返回被导出的 span 名（顺序 = 结束顺序）。 */
    private static List<String> run(boolean dropSecurityFilterSpans, Consumer<Tracer> scenario) {
        Capturing captured = new Capturing();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(
                        new LangfuseTracingConfig.SafeExporter(captured, dropSecurityFilterSpans)))
                .build();
        try {
            scenario.accept(provider.get("safe-exporter-test"));
        } finally {
            provider.close();
        }
        return captured.names;
    }

    /**
     * 回归测试（本类存在的原因）：actuator 抓取的**子 span 先结束**。
     *
     * <p>修复前，子 span 到达导出边界时根还没到 → traceId 没被记住 → 子 span 被导出，
     * 随后根被丢 → Langfuse 里留下一条没有根的无名 trace。加入拒绝表后应为 0 条导出。</p>
     */
    @Test
    void actuatorChildEndingBeforeRoot_isNotExported() {
        List<String> exported = run(true, t -> {
            Span root = t.spanBuilder("http get /actuator/prometheus").startSpan();
            Span child = t.spanBuilder("secured request").startSpan();
            child.end();                       // 子先结束（真实顺序）
            root.end();
        });
        assertEquals(List.of(), exported,
                "actuator 的子 span 不得被导出（否则会变成无名碎片），根 span 也必须被丢");
    }

    /** 对照：关掉拒绝表 → 子 span 就会被导出（这正是修复前的行为，说明该开关有效）。 */
    @Test
    void withFlagOff_theSameScenario_leaksTheOrphan() {
        List<String> exported = run(false, t -> {
            Span root = t.spanBuilder("http get /actuator/prometheus").startSpan();
            Span child = t.spanBuilder("secured request").startSpan();
            child.end();
            root.end();
        });
        assertEquals(List.of("secured request"), exported,
                "关掉开关后应复现修复前的行为（孤儿被导出）—— 这是本回归测试的对照");
    }

    /** 业务请求：根保留、安全过滤链 span 丢掉。 */
    @Test
    void businessRequest_keepsRootButDropsSecuritySpans() {
        List<String> exported = run(true, t -> {
            Span root = t.spanBuilder("http get /auth/me").startSpan();
            Span secured = t.spanBuilder("secured request").startSpan();
            secured.end();
            Span chain = t.spanBuilder("security filterchain before").startSpan();
            chain.end();
            root.end();
        });
        assertEquals(List.of("http get /auth/me"), exported);
    }

    /** 业务 span 一个都不能少（防止拒绝表写宽了）。 */
    @Test
    void businessSpans_areNotDropped() {
        List<String> exported = run(true, t -> {
            Span root = t.spanBuilder("http post /Love_app/chat/sse").startSpan();
            Span graph = t.spanBuilder("graph.node").startSpan();
            Span llm = t.spanBuilder("llm.attempt").startSpan();
            llm.end();
            graph.end();
            Span chain = t.spanBuilder("security filterchain after").startSpan();
            chain.end();
            root.end();
        });
        assertTrue(exported.contains("http post /Love_app/chat/sse"), exported.toString());
        assertTrue(exported.contains("graph.node"), exported.toString());
        assertTrue(exported.contains("llm.attempt"), exported.toString());
        assertFalse(exported.contains("security filterchain after"), exported.toString());
    }

    /** 判据本身：四个实测到的名字要认，null 与业务名不能误伤。 */
    @Test
    void isSecurityFilterSpan_matchesObservedNamesOnly() {
        assertTrue(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("secured request"));
        assertTrue(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("authorize request"));
        assertTrue(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("security filterchain before"));
        assertTrue(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("security filterchain after"));
        assertFalse(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("llm.attempt"));
        assertFalse(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("http get /auth/me"));
        assertFalse(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan("task reflection-scheduler.scan-and-reflect"));
        assertFalse(LangfuseTracingConfig.SafeExporter.isSecurityFilterSpan(null));
    }
}
