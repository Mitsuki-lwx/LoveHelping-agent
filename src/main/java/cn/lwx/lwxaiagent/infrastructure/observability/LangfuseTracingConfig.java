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
import java.util.concurrent.ConcurrentHashMap;

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
        // ADR-62/F6：导出"生效"必须有可查询证据。此前启用后**一条日志都没有** ——
        // 冒烟时我 grep 不到任何 langfuse 字样，只能靠"去实例里查有没有新 trace"反推。
        org.slf4j.LoggerFactory.getLogger(LangfuseTracingConfig.class).info(
                "[langfuse] 导出已启用：endpoint={}（trace 元数据，**不含原文**，ADR-44）", endpoint);
        String credentials = Base64.getEncoder().encodeToString((p.getPublicKey() + ":" + p.getSecretKey()).getBytes(StandardCharsets.UTF_8));
        return builder -> {
            SpanExporter exporter = new SafeExporter(OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint).addHeader("Authorization", "Basic " + credentials)
                    .addHeader("x-langfuse-ingestion-version", "4")
                    .setTimeout(Duration.ofMillis(p.getTimeoutMs())).build(),
                    p.isDropSecurityFilterSpans());
            builder.addSpanProcessor(new SessionProcessor());
            // 采样器不在这里 setSampler：Boot 自带的 sampler customizer 会把它覆盖掉（实测无效）。
            // 改为注册 Sampler Bean（见本类 actuatorAwareSampler()），由 Boot 的 customizer 采用。
            builder.addSpanProcessor(BatchSpanProcessor.builder(exporter)
                    .setMaxQueueSize(p.getMaxQueueSize()).setMaxExportBatchSize(p.getMaxBatchSize())
                    .setScheduleDelay(Duration.ofMillis(p.getScheduleDelayMs()))
                    .setExporterTimeout(Duration.ofMillis(p.getTimeoutMs())).build());
        };
    }

    /**
     * [未采用] /actuator 流量整棵 span 树都不采样的尝试。
     *
     * <p><b>实测无效</b>，故不注册：① 在本类 builder customizer 里 {@code builder.setSampler(...)} 会被
     * Spring Boot 自带的 sampler customizer 覆盖；② 改为注册 {@code Sampler} Bean 后，actuator 根 span
     * 依旧出现，其子 span 也未按 parentBased 继承丢弃（3 次抓取仍留 1~2 条孤儿子 span）。
     * 保留代码是为了记下"试过什么、为什么不成立"，不要在没弄清根因前重新启用。</p>
     */
    static final class ActuatorAwareSampler implements io.opentelemetry.sdk.trace.samplers.Sampler {
        private final io.opentelemetry.sdk.trace.samplers.Sampler delegate =
                io.opentelemetry.sdk.trace.samplers.Sampler.parentBased(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn());

        @Override
        public io.opentelemetry.sdk.trace.samplers.SamplingResult shouldSample(
                Context parentContext, String traceId, String name, io.opentelemetry.api.trace.SpanKind spanKind,
                Attributes attributes, List<LinkData> parentLinks) {
            if (name != null && name.toLowerCase().contains("/actuator")
                    || attributes.asMap().values().stream().anyMatch(v -> v instanceof String s && s.contains("/actuator")))
                return io.opentelemetry.sdk.trace.samplers.SamplingResult.drop();
            return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        @Override
        public String getDescription() {
            return "ActuatorAwareSampler{drops /actuator traces, inherits drop to children}";
        }
    }

    /** Copies non-sensitive session metadata without network baggage propagation. */
    static final class SessionProcessor implements SpanProcessor {
        private static final List<String> KEYS = List.of("langfuse.session.id", "langfuse.user.id", "langfuse.trace.name");
        @Override public void onStart(Context parent, ReadWriteSpan span) {
            if (Span.fromContext(parent) instanceof ReadableSpan p) {
                if ("llm.attempt".equals(p.getName()) || "gateway".equals(p.getAttribute(AttributeKey.stringKey("llm.usage.owner"))))
                    span.setAttribute("llm.usage.owner", "gateway");
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
        private final boolean dropSecurityFilterSpans;
        /** 已判定为 actuator 的 traceId 短期记忆：用于丢弃落在后续批次里的子 span。 */
        private final Set<String> actuatorTraces = ConcurrentHashMap.newKeySet();
        static final int TRACE_MEMORY_LIMIT = 512;
        SafeExporter(SpanExporter delegate) { this(delegate, true); }
        SafeExporter(SpanExporter delegate, boolean dropSecurityFilterSpans) {
            this.delegate = delegate;
            this.dropSecurityFilterSpans = dropSecurityFilterSpans;
        }

        /**
         * Spring Security 过滤链的 span 名（本项目实测到的四个）。
         *
         * <p><b>为什么按名字丢，而不是靠"记住 actuator traceId"</b>：根 span **最后**结束，
         * 子 span 先落盘 —— 子 span 到达导出边界时，根还没到，traceId 自然还没被记住，
         * 于是"按 traceId 整条丢"对**先到的子 span**无效（它们已经出去了）。
         * 子 span 的名字是固定的（Spring Security 给的），且它们**不可导出任何属性**
         * （白名单清空后 metadata.attributes = {}），所以按名字丢是这里唯一可靠的判据。</p>
         *
         * <p>2026-09-24 A/B 实测：6 次 /actuator/prometheus → 结构性无名碎片；
         * 6 次业务端点 → 0 条。加入本拒绝表后重跑，A 组应为 0。</p>
         */
        static boolean isSecurityFilterSpan(String name) {
            if (name == null) return false;
            return "secured request".equals(name)
                    || "authorize request".equals(name)
                    || name.startsWith("security filterchain");
        }
        void remember(String traceId) {
            if (actuatorTraces.size() >= TRACE_MEMORY_LIMIT) actuatorTraces.clear();
            actuatorTraces.add(traceId);
        }
        private static final Set<String> EXACT = Set.of(
                "langfuse.session.id", "langfuse.user.id", "langfuse.trace.name", "langfuse.observation.type",
                "langfuse.observation.model.name", "langfuse.observation.usage_details", "langfuse.observation.level",
                "http.request.method", "http.method", "http.response.status_code", "http.status_code", "http.route",
                "gen_ai.request.model", "gen_ai.response.model", "gen_ai.operation.name", "gen_ai.system",
                "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "gen_ai.usage.prompt_tokens", "gen_ai.usage.completion_tokens",
                "llm.provider", "llm.attempt", "llm.outcome", "llm.endpoint", "graph.route", "graph.node", "graph.outcome",
                // ADR-62/F2+F3：把两条"绕网关"路径与 embedding 变可见（值均**不含原文**）
                "embedding.model", "embedding.batch", "embedding.outcome",
                "jev.field", "jev.outcome", "vision.images", "vision.outcome",
                "rag.candidates", "rag.results", "rag.outcome", "rag.mode", "tool.name", "tool.outcome",
                "error.category", "chat.outcome", "langfuse.observation.status_message");
        /** 已被警告过的 key（每个只警告一次）—— 出口是**白名单**，未列出的 tag 会被丢弃。 */
        private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();
        private static final int WARNED_LIMIT = 64;

        /**
         * ADR-62/F4-根因：本导出边界是**白名单**（见 {@link #EXACT}），未列出的 span tag 会被**丢弃**。
         *
         * <p>⛔ 此前这件事是**静默**的 —— 2026-09-29 我加了一个 {@code llm.endpoint} tag，
         * 运行期实测「tag 已设上」（诊断日志证明），但 Langfuse 里**就是没有**，
         * 直到读到这里才发现被白名单丢了。<b>静默丢观测数据是最难查的一类</b>。</p>
         *
         * <p>保留白名单是刻意的**隐私姿态**（只有经过审查的 key 才出进程，撑住 docs/07 的"不含原文"），
         * 故**不改成黑名单**；改为把丢弃行为**变响**：每个 key 只警告一次。</p>
         */
        private static void warnDroppedOnce(String key) {
            if (key == null || WARNED_KEYS.size() >= WARNED_LIMIT || !WARNED_KEYS.add(key)) return;
            org.slf4j.LoggerFactory.getLogger(SafeExporter.class).warn(
                    "[langfuse] span tag 不在导出白名单，已丢弃：key={}"
                            + "（需要导出请加入 SafeExporter.EXACT；白名单是刻意的隐私边界）", key);
        }

        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            // 一个 actuator 请求的 span 树里，只有根 span 自己带 /actuator 字样；它的子 span
            // （Spring Security 的 secured request / authorize request / security filterchain）
            // 不含路径。只丢根 = 把子 span 变成孤立新根，trace 数量一条没少（实测 3 抓 → 3 条空名 trace）。
            // 因此按 traceId 整条丢，并把判定过的 traceId 记一小段，防止子 span 落在别的批次里。
            for (SpanData span : spans) {
                if (isActuatorTraffic(span)) remember(span.getTraceId());
            }
            List<SpanData> safe = spans.stream()
                    .filter(s -> !actuatorTraces.contains(s.getTraceId()))
                    // 见 isSecurityFilterSpan 的注释：这些孤儿必须丢，否则 Langfuse 里
                    // 每次 Prometheus 抓取都可能留下一条无名 trace（实测 30 分钟窗口里碎片占 83%）。
                    .filter(s -> !(dropSecurityFilterSpans && isSecurityFilterSpan(s.getName())))
                    .map(s -> (SpanData) new DelegatingSpanData(s) {
                @Override public Attributes getAttributes() {
                    var out = Attributes.builder();
                    // Only a span the gateway actually owns (marked by SessionProcessor under an
                    // llm.attempt) loses its own usage. A standalone SDK chat span keeps its usage.
                    boolean sdkChat = "gateway".equals(s.getAttributes().get(AttributeKey.stringKey("llm.usage.owner")));
                    s.getAttributes().forEach((key, value) -> {
                        if (!EXACT.contains(key.getKey()) || key.getKey().equals("langfuse.observation.status_message")) {
                            warnDroppedOnce(key.getKey());
                            return;
                        }
                        if (sdkChat && (key.getKey().startsWith("gen_ai.usage.") || key.getKey().equals("langfuse.observation.usage_details"))) return;
                        put(out, key, value);
                    });
                    // The gateway is the single generation/usage owner. SDK timing remains a child span.
                    if (sdkChat) out.put("langfuse.observation.type", "span");
                    String category = s.getAttributes().get(AttributeKey.stringKey("error.category"));
                    if (category != null && category.matches("backpressure|timeout|transport|cancelled|execution_failed|http_[0-9]{3}"))
                        out.put("langfuse.observation.status_message", category);
                    return out.build();
                }
                @Override public List<EventData> getEvents() { return List.of(); }
                @Override public StatusData getStatus() { return StatusData.create(s.getStatus().getStatusCode(), ""); }
            }).toList();
            return delegate.export(safe);
        }
        /**
         * /actuator 流量（Prometheus 每 15s 一次抓取）在采样率 1.0 下每个周期产生一条根 trace，
         * 生产约 5760 条/天，会把 Langfuse 的 trace 列表窗口挤满（实测已导致按 traceId 反查未命中）。
         *
         * <p>只在<b>导出边界</b>丢弃：指标口径（http.server.requests 等）保持原样，
         * 不在观测层拦截——那会顺带干掉 actuator 自身的指标。</p>
         *
         * <p>判定不绑定单一属性名：不同 instrumentation 对 URI 的命名不一致
         * （url.path / http.target / url.full …），写死一个键会在升级后静默失效，
         * 而"静默失效"正是本项要消除的东西。可导出白名单里没有业务内容，
         * 因此扫描全部字符串属性不会误伤。</p>
         */
        static boolean isActuatorTraffic(SpanData span) {
            String name = span.getName();
            if (name != null && name.contains("/actuator")) return true;
            for (var entry : span.getAttributes().asMap().entrySet()) {
                if (entry.getValue() instanceof String value && value.contains("/actuator")) return true;
            }
            return false;
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        private static void put(io.opentelemetry.api.common.AttributesBuilder out, AttributeKey key, Object value) { out.put(key, value); }
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }
}
