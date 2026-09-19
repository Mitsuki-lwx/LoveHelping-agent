package cn.lwx.lwxaiagent.infrastructure.observability;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LangfuseTracingTest {
    static class RecordingExporter implements SpanExporter {
        List<SpanData> received = new CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> spans) { received.addAll(spans); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
    @Test void exportedTraceKeepsParentUsageAndDropsSensitiveAttributesAndEvents() {
        RecordingExporter recorder = new RecordingExporter();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(new LangfuseTracingConfig.SessionProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(new LangfuseTracingConfig.SafeExporter(recorder))).build()) {
            var tracer = provider.get("test"); var root = tracer.spanBuilder("chat.pipeline").startSpan();
            root.setAttribute("langfuse.session.id", "opaque-session"); root.setAttribute("langfuse.trace.name", "chat");
            try (var scope = root.makeCurrent()) {
                var child = tracer.spanBuilder("llm.attempt").startSpan();
                child.setAttribute("langfuse.observation.type", "generation");
                child.setAttribute("langfuse.observation.usage_details", "{\"input\":10,\"output\":4}");
                child.setAttribute("gen_ai.prompt", "sensitive prompt"); child.setAttribute("url.full", "http://example/?token=private");
                child.recordException(new RuntimeException("private upstream response"));
                child.setStatus(StatusCode.ERROR, "private detail"); child.end();
            } finally { root.end(); }
            var child = recorder.received.stream().filter(s -> s.getName().equals("llm.attempt")).findFirst().orElseThrow();
            assertEquals(root.getSpanContext().getTraceId(), child.getTraceId());
            assertEquals(root.getSpanContext().getSpanId(), child.getParentSpanId());
            assertEquals("opaque-session", child.getAttributes().get(AttributeKey.stringKey("langfuse.session.id")));
            assertNotNull(child.getAttributes().get(AttributeKey.stringKey("langfuse.observation.usage_details")));
            assertNull(child.getAttributes().get(AttributeKey.stringKey("gen_ai.prompt")));
            assertNull(child.getAttributes().get(AttributeKey.stringKey("url.full")));
            assertTrue(child.getEvents().isEmpty()); assertEquals("", child.getStatus().getDescription());
            assertEquals(StatusCode.ERROR, child.getStatus().getStatusCode());
        }
    }
    @Test void otlpHttpPayloadUsesConfiguredEndpointAuthAndFlushes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> auth = new AtomicReference<>(); AtomicReference<byte[]> payload = new AtomicReference<>();
        server.createContext("/api/public/otel/v1/traces", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization")); payload.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().close(); received.countDown();
        }); server.start();
        LangfuseProperties p = new LangfuseProperties(); p.setEnabled(true);
        p.setHost("http://127.0.0.1:" + server.getAddress().getPort());
        p.setPublicKey(UUID.randomUUID().toString()); p.setSecretKey(UUID.randomUUID().toString());
        p.setScheduleDelayMs(10); p.setTimeoutMs(500); p.setMaxQueueSize(16); p.setMaxBatchSize(4);
        var builder = SdkTracerProvider.builder(); new LangfuseTracingConfig().langfuseExporter(p).customize(builder);
        try (var provider = builder.build()) {
            provider.get("test").spanBuilder("chat.pipeline").startSpan().end();
            provider.forceFlush().join(2, TimeUnit.SECONDS);
            assertTrue(received.await(2, TimeUnit.SECONDS));
            String expected = "Basic " + Base64.getEncoder().encodeToString((p.getPublicKey() + ":" + p.getSecretKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(expected, auth.get()); assertTrue(payload.get().length > 0);
        } finally { server.stop(0); }
    }
    @Test void sdkChatDoesNotDoubleCountGatewayGenerationTokens() {
        RecordingExporter recorder = new RecordingExporter();
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(new LangfuseTracingConfig.SessionProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(new LangfuseTracingConfig.SafeExporter(recorder))).build()) {
            var tracer = provider.get("test");
            var attempt = tracer.spanBuilder("llm.attempt").startSpan();
            attempt.setAttribute("langfuse.observation.type", "generation");
            attempt.setAttribute("langfuse.observation.usage_details", "{\"input\":20,\"output\":4}");
            try (var scope = attempt.makeCurrent()) {
                var sdk = tracer.spanBuilder("chat test-model").startSpan();
                sdk.setAttribute("gen_ai.operation.name", "chat");
                sdk.setAttribute("gen_ai.request.model", "test-model");
                sdk.setAttribute("gen_ai.usage.input_tokens", 20L);
                sdk.setAttribute("gen_ai.usage.output_tokens", 4L);
                sdk.setAttribute("langfuse.observation.status_message", "private upstream body");
                sdk.end();
            } finally { attempt.end(); }
            var sdk = recorder.received.stream().filter(s -> s.getName().startsWith("chat ")).findFirst().orElseThrow();
            assertEquals("span", sdk.getAttributes().get(AttributeKey.stringKey("langfuse.observation.type")));
            assertNull(sdk.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
            assertNull(sdk.getAttributes().get(AttributeKey.stringKey("langfuse.observation.status_message")));
            assertEquals(attempt.getSpanContext().getSpanId(), sdk.getParentSpanId());
            var gateway = recorder.received.stream().filter(s -> s.getName().equals("llm.attempt")).findFirst().orElseThrow();
            assertNotNull(gateway.getAttributes().get(AttributeKey.stringKey("langfuse.observation.usage_details")));
        }
    }

    @Test void standaloneSdkChatRetainsItsOwnUsage() {
        RecordingExporter recorder = new RecordingExporter();
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(new LangfuseTracingConfig.SessionProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(new LangfuseTracingConfig.SafeExporter(recorder))).build()) {
            var sdk = provider.get("test").spanBuilder("chat standalone").startSpan();
            sdk.setAttribute("gen_ai.operation.name", "chat");
            sdk.setAttribute("gen_ai.usage.input_tokens", 12L);
            sdk.end();
            assertEquals(12L, recorder.received.getFirst().getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
            assertNull(recorder.received.getFirst().getAttributes().get(AttributeKey.stringKey("langfuse.observation.type")));
        }
    }

    @Test void missingLangfuseCredentialsFailFastNotSilently() {
        assertThrows(IllegalArgumentException.class, () -> new LangfuseTracingConfig().langfuseExporter(new LangfuseProperties()));
    }
    @Test void exporterOutageCannotBlockSpanCreation() {
        var failing = new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> data) { return CompletableResultCode.ofFailure(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofFailure(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(failing)
                .setMaxQueueSize(8).setMaxExportBatchSize(4).build()).build()) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                for (int i = 0; i < 1000; i++) provider.get("test").spanBuilder("work").startSpan().end();
            });
        }
    }

    /**
     * /actuator 流量在导出边界被丢弃，业务 span 不受影响。
     *
     * <p>Prometheus 每 15s 抓一次 /actuator/prometheus，采样率 1.0 下每个周期一条根 trace，
     * 生产约 5760 条/天，会把 Langfuse 的 trace 列表窗口挤满。判定不绑定单一属性名
     * （url.path / http.target / url.full 命名不一致，写死一个键会静默失效）。</p>
     */
    @Test void actuatorTrafficIsDroppedAtExportBoundaryWhileBusinessSpansSurvive() {
        RecordingExporter raw = new RecordingExporter();
        List<SpanData> captured;
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(raw)).build()) {
            var tracer = provider.get("test");
            var scrape = tracer.spanBuilder("GET").startSpan();
            scrape.setAttribute("url.path", "/actuator/prometheus");
            scrape.setAttribute("http.request.method", "GET");
            // 真实结构：Spring Security 的观测是**同一 trace 下**的子 span，它们自身不含路径字样
            try (var scope = scrape.makeCurrent()) {
                var childA = tracer.spanBuilder("secured request").startSpan();
                var childB = tracer.spanBuilder("authorize request").startSpan();
                childA.end();
                childB.end();
            }
            scrape.end();
            var scrapeNamed = tracer.spanBuilder("GET /actuator/health").startSpan();
            scrapeNamed.end();
            var chat = tracer.spanBuilder("chat.pipeline").startSpan();
            chat.setAttribute("url.path", "/api/Love_app/chat/sse");
            chat.setAttribute("langfuse.trace.name", "chat");
            chat.end();
            captured = List.copyOf(raw.received);
        }
        assertEquals(5, captured.size(), "前置条件：本批次应当是 5 条 span（1 根 + 2 子 + 1 健康检查 + 1 业务）");
        assertEquals(captured.get(0).getTraceId(), captured.get(1).getTraceId(),
                "前置条件：子 span 必须与根 span 同 trace（否则测的不是真实结构）");

        RecordingExporter out = new RecordingExporter();
        new LangfuseTracingConfig.SafeExporter(out).export(captured);

        // 只丢根会把子 span 留成无名 trace（实测：3 次抓取 → 3 条空名 trace，数量一条没少）→ 必须按 traceId 整条丢
        assertEquals(List.of("chat.pipeline"), out.received.stream().map(SpanData::getName).toList(),
                "同一批次里应当丢掉 actuator 整条 trace（含其子 span），业务 span 原样导出");
        assertEquals("chat",
                out.received.getFirst().getAttributes().get(AttributeKey.stringKey("langfuse.trace.name")));
    }

    /**
     * 跨批次兜底：子 span 自身不含路径字样，若它与根 span 落在不同批次，
     * 先到的那批无法预知；根 span 到达后必须封住该 traceId，后续同 trace 的 span 一律丢弃。
     */
    @Test void actuatorTraceIdMemoryDropsStragglersInLaterBatches() {
        RecordingExporter raw = new RecordingExporter();
        List<SpanData> captured;
        try (var provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(raw)).build()) {
            var tracer = provider.get("test");
            var root = tracer.spanBuilder("GET").startSpan();
            root.setAttribute("url.path", "/actuator/prometheus");
            try (var scope = root.makeCurrent()) {
                var c = tracer.spanBuilder("secured request").startSpan();
                c.end();
            }
            root.end();
            captured = List.copyOf(raw.received);
        }
        SpanData child = captured.get(0);
        assertEquals("secured request", child.getName(), "前置条件：子 span 先结束、先导出");

        RecordingExporter out = new RecordingExporter();
        var exporter = new LangfuseTracingConfig.SafeExporter(out);
        exporter.export(List.of(child));              // 先到的一批：还不知道这是 actuator（子 span 无路径）→ 漏
        exporter.export(List.of(captured.get(1)));    // 根 span 到达 → 记住 traceId
        exporter.export(List.of(child));              // 同 traceId 的后续 span → 靠记忆拦住

        assertEquals(List.of("secured request"), out.received.stream().map(SpanData::getName).toList(),
                "根 span 到达后必须封住该 traceId；先到的那一批是会漏的已知局限");
    }
}
