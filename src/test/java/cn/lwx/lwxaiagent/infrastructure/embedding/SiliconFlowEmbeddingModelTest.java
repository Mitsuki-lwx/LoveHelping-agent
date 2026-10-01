package cn.lwx.lwxaiagent.infrastructure.embedding;

import cn.lwx.lwxaiagent.config.SiliconFlowProperties;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h3>嵌入适配器的**协议契约**（此前该类覆盖率 0%，却在 RAG 主路径上）</h3>
 *
 * <p>类注释写着「**不吞异常**：上游非 200、响应结构异常、维度不符一律抛」，
 * 以及「**宁可失败暴露，也不写入错维度向量污染库**」。这些契约此前**没有一条被测过** ——
 * 而写错维度到 {@code vector_store.embedding vector(1024)} 正是 ADR-46 同族（静默污染检索）。</p>
 *
 * <p>用 JDK 自带的 {@link HttpServer} 当上游打桩，不引新依赖、不需要网络。</p>
 */
@DisplayName("嵌入适配器：协议契约")
class SiliconFlowEmbeddingModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXPECTED = 1024;

    private static AiTelemetry telemetry() {
        return new AiTelemetry(Tracer.NOOP);
    }

    private static SiliconFlowProperties props(String baseUrl, String apiKey) {
        SiliconFlowProperties p = new SiliconFlowProperties();
        p.setBaseUrl(baseUrl);
        p.setApiKey(apiKey);
        p.setMaxBatchSize(32);
        return p;
    }

    private static SiliconFlowEmbeddingModel model(String baseUrl, String apiKey) {
        return new SiliconFlowEmbeddingModel(props(baseUrl, apiKey), JSON, telemetry());
    }

    /** 打桩上游：固定状态码 + 固定响应体。 */
    private static HttpServer stub(int status, String body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/embeddings", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    private static String embeddingsJson(int dim, int count) {
        StringBuilder sb = new StringBuilder("{\"model\":\"m\",\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"embedding\":[");
            for (int d = 0; d < dim; d++) {
                if (d > 0) sb.append(',');
                sb.append("0.01");
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("声明维度必须是 1024 —— 它与 vector_store.embedding vector(1024) 绑定")
    void dimensions_is_1024() {
        assertThat(model("https://api.siliconflow.cn", "k").dimensions()).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("baseUrl 非法时**构造就抛**（不许把坏配置带进运行期）")
    void invalid_base_url_fails_fast() {
        assertThatThrownBy(() -> model("不是URL", "k"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid siliconflow base URL");
    }

    @Test
    @DisplayName("embed(null) 抛 IllegalArgumentException（不许 NPE 糊过去）")
    void embed_null_document_rejected() {
        assertThatThrownBy(() -> model("https://api.siliconflow.cn", "k").embed((Document) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空请求返回空结果，且 **usage 非 null**（ADR-71 修：原先传 null 给非空参数）")
    void empty_request_returns_empty_with_nonnull_usage() {
        EmbeddingResponse resp = model("https://api.siliconflow.cn", "k").call(null);
        assertThat(resp.getResults()).isEmpty();
        assertThat(resp.getMetadata().getUsage())
                .as("consumer 解引用 getUsage() 不该 NPE；空用量用 EmptyUsage 表达")
                .isNotNull();
    }

    @Test
    @DisplayName("未配 API Key → IllegalStateException，且错误里给出**排障指引**（不吞异常）")
    void missing_api_key_is_loud() {
        assertThatThrownBy(() -> model("https://api.siliconflow.cn", "  ").embed(List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SF_API_KEY")
                .hasMessageContaining("provider");
    }

    @Test
    @DisplayName("上游返回 1024 维 → 正常取回")
    void happy_path_returns_vector() throws IOException {
        HttpServer s = stub(200, embeddingsJson(EXPECTED, 1));
        try {
            List<float[]> out = model("http://127.0.0.1:" + s.getAddress().getPort(), "k").embed(List.of("你好"));
            assertThat(out).hasSize(1);
            assertThat(out.get(0)).hasSize(EXPECTED);
        } finally {
            s.stop(0);
        }
    }

    @Test
    @DisplayName("⛔ 上游返回 512 维 → **必须抛**（宁可失败暴露，也不写入错维度向量污染库）")
    void wrong_dimension_is_rejected() throws IOException {
        HttpServer s = stub(200, embeddingsJson(512, 1));
        try {
            SiliconFlowEmbeddingModel m = model("http://127.0.0.1:" + s.getAddress().getPort(), "k");
            assertThatThrownBy(() -> m.embed(List.of("你好")))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            s.stop(0);
        }
    }

    @Test
    @DisplayName("上游非 200 → 必须抛（不吞异常），消息里带状态码")
    void http_error_is_loud() throws IOException {
        HttpServer s = stub(500, "{\"error\":\"boom\"}");
        try {
            SiliconFlowEmbeddingModel m = model("http://127.0.0.1:" + s.getAddress().getPort(), "k");
            assertThatThrownBy(() -> m.embed(List.of("你好")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("500");
        } finally {
            s.stop(0);
        }
    }
}
