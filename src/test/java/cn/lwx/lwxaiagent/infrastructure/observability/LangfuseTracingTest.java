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
}
