package cn.lwx.lwxaiagent.infrastructure.embedding;

import cn.lwx.lwxaiagent.config.SiliconFlowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>硅基流动（SiliconFlow）EmbeddingModel 实现</h1>
 *
 * <p>手写 {@link EmbeddingModel}，直接调用 {@code POST /v1/embeddings}（OpenAI 兼容协议），
 * 而不复用 Spring AI 的 {@code OpenAiEmbeddingModel}——原因是本项目的
 * {@code spring.ai.openai} 前缀已被智谱占用（见 {@link SiliconFlowProperties} 注释），
 * 复用会造成配置语义混淆。</p>
 *
 * <h3>协议契约（实测，2026-09-20 离线评测已验证）</h3>
 * <pre>
 * 请求：POST {baseUrl}/v1/embeddings
 *       Authorization: Bearer &lt;SF_API_KEY&gt;
 *       {"model":"Qwen/Qwen3-Embedding-0.6B","input":["文本1","文本2"],"encoding_format":"float"}
 * 响应：{"data":[{"object":"embedding","index":0,"embedding":[...]}],
 *        "model":"...","usage":{"prompt_tokens":N,"total_tokens":N}}
 * </pre>
 *
 * <h3>维度约束</h3>
 * <p>Qwen3-Embedding-0.6B 输出 <b>1024 维</b>，与现有 {@code vector_store.embedding vector(1024)}
 * 完全兼容。<b>绝不能切换为 4B/8B</b>——它们分别输出 2560/4096 维，与现有列类型冲突。</p>
 *
 * <h3>失败语义</h3>
 * <p>与项目既有约定一致：<b>不吞异常</b>。上游非 200、响应结构异常、维度不符一律抛
 * {@link IllegalStateException}，由上层（PgVectorStore 写入 / 检索链路）决定降级或报错。
 * 静默返回零向量会让"检索到了但内容不对"这种最坏故障变得不可观测。</p>
 *
 * @author lwx
 * @since Phase 8 (ADR-36)
 */
public class SiliconFlowEmbeddingModel implements EmbeddingModel {

    /** 硅基流动 embedding 端点路径（baseUrl 已去掉尾部斜杠）。 */
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    /** 期望维度：与 vector_store.embedding vector(1024) 绑定，不符即视为协议违约。 */
    private static final int EXPECTED_DIMENSIONS = 1024;

    /** 单条文本最大字符数，防超长导致 400（Qwen3-Embedding 上下文约 32k token）。 */
    private static final int MAX_CHARS = 8000;

    private final SiliconFlowProperties props;
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI endpoint;

    public SiliconFlowEmbeddingModel(SiliconFlowProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        String base = props.getBaseUrl() == null ? "" : props.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base + EMBEDDINGS_PATH);
        // 与 LocalDocumentReranker 同款校验：只允许 http/https，且不得带 userInfo（防密钥进 URL 被日志记录）
        if (!java.util.Set.of("http", "https").contains(endpoint.getScheme())
                || endpoint.getHost() == null || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid siliconflow base URL: " + props.getBaseUrl());
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------------
    // EmbeddingModel 接口实现
    // ------------------------------------------------------------------

    /**
     * 抽象方法（接口唯一强制实现）：单文档嵌入。
     * <p>注意 Spring AI 1.1.x 中 Document 的取文本方法是 {@code getText()}（旧版为 getContent）。</p>
     */
    @Override
    public float[] embed(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        List<float[]> vectors = doEmbed(List.of(text(document)));
        return vectors.get(0);
    }

    /** 批量嵌入（接口 default 方法会走这里做真正的批处理，避免逐条 HTTP）。 */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return doEmbed(texts);
    }

    /**
     * 通用调用入口。{@code EmbeddingRequest} 的 instructions 即待嵌入文本列表。
     * <p>PgVectorStore 在写入与检索时都走这条路径，因此这里是热路径——
     * 批量一次性发出，不做逐条请求。</p>
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request == null ? null : request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of(), new EmbeddingResponseMetadata(modelName(), null));
        }
        List<float[]> vectors = doEmbed(texts);
        List<Embedding> embeddings = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(modelName(), null));
    }

    /** 声明维度，供 Spring AI 内部（如 PgVectorStore 建表/校验）使用。 */
    @Override
    public int dimensions() {
        return EXPECTED_DIMENSIONS;
    }

    /**
     * 取文档文本。优先用基类默认实现（会处理多模态等情况），
     * 不可用时回退 {@code getText()}。
     */
    private String text(Document document) {
        try {
            String content = EmbeddingModel.super.getEmbeddingContent(document);
            if (content != null && !content.isBlank()) {
                return content;
            }
        } catch (RuntimeException ignored) {
            // 默认实现依赖 metadata 里的文本键，缺失时可能抛异常——回退到显式取文本
        }
        return document.getText();
    }

    // ------------------------------------------------------------------
    // HTTP 调用与解析
    // ------------------------------------------------------------------

    /**
     * 按 {@code maxBatchSize} 分批调用上游，拼接结果。
     * <p>分批而非一次全发：硅基流动对单次 input 条数有上限（实测 32 稳妥），
     * 且 439 块知识库一次全发会触发超时。</p>
     */
    private List<float[]> doEmbed(List<String> texts) {
        if (!props.hasApiKey()) {
            throw new IllegalStateException(
                    "SF_API_KEY 未配置：硅基流动 embedding 不可用。"
                            + "请在环境变量 SF_API_KEY 中注入，或把 app.rag.embedding.provider 切回 dashscope");
        }
        int batch = Math.max(1, props.getMaxBatchSize());
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batch) {
            int to = Math.min(texts.size(), from + batch);
            out.addAll(callOnce(texts.subList(from, to)));
        }
        return out;
    }

    /** 单批请求：构造请求体 → 发送 → 解析 → 校验维度。 */
    private List<float[]> callOnce(List<String> texts) {
        List<String> payload = texts.stream().map(SiliconFlowEmbeddingModel::clip).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName());
        body.put("input", payload);
        // 显式要求 float：部分兼容层默认返回 base64，解析成本更高
        body.put("encoding_format", "float");

        try {
            String requestJson = json.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                // 截断响应体防日志爆炸（密钥不会出现在响应里，但错误体可能很长）
                String excerpt = response.body() == null ? "" : clip(response.body(), 500);
                throw new IllegalStateException(
                        "siliconflow embeddings HTTP " + response.statusCode() + ": " + excerpt);
            }
            return parse(response.body(), texts.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("siliconflow embeddings interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("siliconflow embeddings failed", e);
        }
    }

    /**
     * 解析响应并校验。
     * <p>三道校验缺一不可：①data 是数组且条数与请求一致；②每条 embedding 是数组；
     * ③维度 == 1024。任一不满足即抛错——宁可失败暴露，也不写入错维度向量污染库。</p>
     */
    List<float[]> parse(String body, int expectedCount) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != expectedCount) {
                throw new IllegalStateException("siliconflow embeddings count mismatch: expect "
                        + expectedCount + " got " + (data.isArray() ? data.size() : "non-array"));
            }
            // 上游可能乱序返回，按 index 归位（协议规定有 index 字段）
            float[][] slots = new float[expectedCount][];
            for (JsonNode row : data) {
                JsonNode indexNode = row.path("index");
                int index = indexNode.isInt() ? indexNode.asInt() : -1;
                if (index < 0 || index >= expectedCount) {
                    throw new IllegalStateException("siliconflow embeddings invalid index: " + index);
                }
                JsonNode vec = row.path("embedding");
                if (!vec.isArray() || vec.isEmpty()) {
                    throw new IllegalStateException("siliconflow embeddings empty vector at " + index);
                }
                if (vec.size() != EXPECTED_DIMENSIONS) {
                    throw new IllegalStateException("siliconflow embeddings dimension mismatch: expect "
                            + EXPECTED_DIMENSIONS + " got " + vec.size()
                            + "（若换了 embedding 模型，vector_store.embedding 的列类型必须同步重建）");
                }
                float[] values = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) {
                    values[i] = (float) vec.get(i).asDouble();
                }
                slots[index] = values;
            }
            List<float[]> result = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                if (slots[i] == null) {
                    throw new IllegalStateException("siliconflow embeddings missing index " + i);
                }
                result.add(slots[i]);
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("siliconflow embeddings parse failed", e);
        }
    }

    private String modelName() {
        return props.getEmbeddingModel() == null || props.getEmbeddingModel().isBlank()
                ? "Qwen/Qwen3-Embedding-0.6B"
                : props.getEmbeddingModel();
    }

    /** 按字符数截断，避免超长请求被上游拒绝。 */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS);
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
