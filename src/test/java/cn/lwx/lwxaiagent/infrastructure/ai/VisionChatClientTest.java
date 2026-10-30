package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.config.LlmProviderProperties;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VisionChatClient 单测：用**本地 HttpServer 桩**（非 mock 框架），模式同 {@code JevClientTest}。
 *
 * <p>为什么要永久守这三条（2026-10-07 实测踩出来的）：</p>
 * <ol>
 *   <li><b>端点必须与聊天链同源</b>：原先这里读 {@code spring.ai.openai.*} —— 把聊天链切到 cline-pass 后，
 *       视觉这条**静默留在 DeepSeek 官方 key 上**。"半迁移"在配置层看不出来。</li>
 *   <li><b>信封必须拆</b>：cline.bot 的非流式响应是 {@code {"data":{...}}}；不拆就
 *       {@code root.path("choices")} 取不到 → {@code asText("")} → <b>静默返回空串</b>，
 *       现象是"模型什么都没说"，极难归因。</li>
 *   <li><b>模型留空要回落到主链模型</b>：否则 yml 里一个过期的默认值会把 provider 的模型覆盖成无效 id。</li>
 * </ol>
 */
class VisionChatClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 只回固定响应的桩；记录收到的请求路径与请求体。 */
    private record Stub(HttpServer server, AtomicReference<String> path, AtomicReference<String> body) implements AutoCloseable {
        static Stub start(String responseBody) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<String> path = new AtomicReference<>();
            AtomicReference<String> body = new AtomicReference<>();
            server.createContext("/", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return new Stub(server, path, body);
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); }
    }

    /** 一个 enabled 的 provider（模拟 cline-pass：base 不含 /v1，带 {data:} 信封）。 */
    private static LlmProviderProperties props(String baseUrl, String envelope, String model) {
        LlmProviderProperties p = new LlmProviderProperties();
        LlmProviderProperties.Provider provider = new LlmProviderProperties.Provider();
        provider.setName("primary");
        provider.setBaseUrl(baseUrl);
        provider.setApiKey("sk-test");
        provider.setModel(model);
        provider.setEnabled(true);
        provider.setResponseEnvelope(envelope);
        p.setProviders(new java.util.ArrayList<>(List.of(provider)));
        return p;
    }

    private static AiTelemetry telemetry() {
        // 视觉链路的观测只做打点。⛔ 必须把 start/scope 桩掉：裸 mock 会返回 null，
        // 于是 span.tag(...) 直接 NPE —— 那不是"测出缺陷"，是"桩没打对"。
        AiTelemetry t = mock(AiTelemetry.class);
        when(t.start(anyString(), any())).thenReturn(mock(Span.class));
        when(t.scope(any())).thenReturn(mock(Tracer.SpanInScope.class));
        return t;
    }

    @Test
    void 走provider的端点与补全路径_而不是springAi的配置() throws Exception {
        try (Stub stub = Stub.start("{\"data\":{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}}")) {
            var client = new VisionChatClient(props(stub.baseUrl(), "data", "cline-pass/deepseek-v4.1-flash"), "", telemetry());
            client.chat("看图", List.of(new byte[]{1, 2, 3}), "image/png");
            // provider 的 completionsPath 默认 /v1/chat/completions（base 是 /api 形态的网关就靠这个拼）
            assertEquals("/v1/chat/completions", stub.path().get());
            assertTrue(stub.body().get().contains("cline-pass/deepseek-v4.1-flash"), "应使用 provider 的模型");
            assertTrue(stub.body().get().contains("data:image/png;base64,"), "图片要以 data URL 发送");
        }
    }

    @Test
    void 有信封时必须拆封_否则静默返回空串() throws Exception {
        try (Stub stub = Stub.start("{\"data\":{\"choices\":[{\"message\":{\"content\":\"信封里的回答\"}}]}}")) {
            var client = new VisionChatClient(props(stub.baseUrl(), "data", "m"), "", telemetry());
            assertEquals("信封里的回答", client.chat("看图", List.of(new byte[]{1}), "image/png"));
        }
    }

    @Test
    void 没有信封时按根解析() throws Exception {
        try (Stub stub = Stub.start("{\"choices\":[{\"message\":{\"content\":\"根上的回答\"}}]}")) {
            var client = new VisionChatClient(props(stub.baseUrl(), null, "m"), "", telemetry());
            assertEquals("根上的回答", client.chat("看图", List.of(new byte[]{1}), "image/png"));
        }
    }

    @Test
    void 模型留空则回落到主链模型_指定则覆盖() throws Exception {
        try (Stub stub = Stub.start("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}")) {
            var byDefault = new VisionChatClient(props(stub.baseUrl(), null, "主链模型"), "  ", telemetry());
            byDefault.chat("看图", List.of(new byte[]{1}), "image/png");
            assertTrue(stub.body().get().contains("主链模型"), "留空/空白应回落主链模型");

            var overridden = new VisionChatClient(props(stub.baseUrl(), null, "主链模型"), "视觉专用", telemetry());
            overridden.chat("看图", List.of(new byte[]{1}), "image/png");
            assertTrue(stub.body().get().contains("视觉专用"), "指定了就用指定的");
        }
    }

    @Test
    void 没有可用provider时启动即失败_不静默指向空端点() {
        LlmProviderProperties empty = new LlmProviderProperties();
        empty.setProviders(new java.util.ArrayList<>());
        assertThrows(IllegalStateException.class, () -> new VisionChatClient(empty, "", telemetry()));
    }
}
