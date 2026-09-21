package cn.lwx.lwxaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <h1>硅基流动（SiliconFlow）API 配置属性</h1>
 *
 * <p>绑定 {@code app.siliconflow.*}。用于 embedding 与 rerank 两个通道——
 * 二者共用同一个 API Key 与 base URL，只是路径不同（{@code /v1/embeddings}、{@code /v1/rerank}）。</p>
 *
 * <p><b>为什么单独开一个 provider 配置块而不复用 {@code spring.ai.openai}？</b>
 * 该前缀在本项目已被智谱（sensenova）占用（{@code base-url: token.sensenova.cn}，
 * 且 {@code embedding.enabled: false} 正是为躲开 {@code @Primary} 冲突而关的）。
 * 复用会导致"嵌入走智谱、对话走智谱"的语义混淆，故独立配置、独立 bean。</p>
 *
 * <p><b>密钥纪律（硬约束）</b>：{@code api-key} 只能来自环境变量 {@code SF_API_KEY}，
 * 在 {@code application.yml} 中写为 {@code ${SF_API_KEY:}} 空占位。
 * <b>严禁把真实密钥写进仓库任何文件</b>。</p>
 *
 * @author lwx
 * @since Phase 8 (ADR-36)
 */
@Data
@ConfigurationProperties(prefix = "app.siliconflow")
public class SiliconFlowProperties {

    /** API Key，来自环境变量 {@code SF_API_KEY}（不落盘）。 */
    private String apiKey = "";

    /** 服务基址，不含路径。默认硅基流动公有云。 */
    private String baseUrl = "https://api.siliconflow.cn";

    /** 嵌入模型名。默认 Qwen3-Embedding-0.6B（1024 维，与现有 vector(1024) 兼容）。 */
    private String embeddingModel = "Qwen/Qwen3-Embedding-0.6B";

    /**
     * 重排模型名。默认 Qwen3-Reranker-8B（离线评测中两套 embedding 下 MRR 均最优，
     * 见 docs/phase8-embedding-rerank-migration/spec.md §2）。
     */
    private String rerankModel = "Qwen/Qwen3-Reranker-8B";

    /** 请求连接超时（毫秒）。 */
    private long connectTimeoutMs = 5000;

    /** 请求读超时（毫秒）。8B 重排实测偶发 3.97s，故默认给到 30s 留足余量。 */
    private long readTimeoutMs = 30000;

    /**
     * 单次调用最多携带的文本条数。硅基流动对 /v1/embeddings 有批大小限制，
     * 分批调用可避免 413/400，并平滑网络流量。
     */
    private int maxBatchSize = 32;

    /** api-key 是否已配置（空串视为未配置，用于启动期校验与日志提示）。 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
