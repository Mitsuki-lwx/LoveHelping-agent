package cn.lwx.lwxaiagent.rag.rerank;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.rag.rerank")
public class RerankProperties {
    private boolean enabled;
    @Min(1) @Max(50) private int topN = 20;
    @Min(1) @Max(50) private int topK = 5;
    /**
     * 重排模式：
     * <ul>
     *   <li>{@code local}：自建本地 CPU cross-encoder（默认，ops/reranker 服务）</li>
     *   <li>{@code remote}：硅基流动 /v1/rerank（Phase 8 / ADR-36 新增，需 SF_API_KEY）</li>
     *   <li>{@code llm}：借 LLM 打分（成本高，仅对照用）</li>
     *   <li>{@code off}：关闭</li>
     * </ul>
     *
     * <p><b>接线注意（防回归）</b>：{@code local} 与 {@code remote} 由**同一个 HTTP 实现**
     * （{@link LocalDocumentReranker}）承载 —— 两者只在 endpoint/鉴权上不同；
     * {@link RerankDocumentPostProcessor} 必须把两者**都**路由给它，只有 {@code llm} 才走主线模型。
     * 2026-09-21 修复：该处此前写成 {@code "local".equals(mode) ? local : llm}，
     * 导致 {@code mode=remote} 被静默路由到 LLM 打分 —— 硅基流动 {@code /v1/rerank}
     * 一次都没被调用，而单测全绿（单测只覆盖组件自身，不覆盖连线）。
     * 现由 {@code RerankDocumentPostProcessorTest} 守住。</p>
     */
    @Pattern(regexp = "local|remote|llm|off") private String mode = "local";
    @Min(1) @Max(1200) private int maxCandidateChars = 1000;
    private String url = "http://127.0.0.1:8091/rerank";
    /**
     * remote 模式下的模型名。默认 Qwen3-Reranker-8B
     * （离线评测：两套 embedding 下 MRR 均为最优，见 docs/phase8-embedding-rerank-migration/spec.md）。
     */
    private String model = "Qwen/Qwen3-Reranker-8B";
    /**
     * 调用超时。默认 <b>5000ms</b>（原 2000）——实测 8B 重排偶发 3.97s，
     * 2000ms 会把正常请求误判为超时并整体降级。
     */
    @Min(1) @Max(10000) private long timeoutMs = 5000;
    @Min(1) @Max(10000) private long connectTimeoutMs = 500;
    @Min(1) @Max(16) private int maxConcurrent = 2;
    @Min(1) private int failureThreshold = 3;
    @Min(1) private long circuitOpenMs = 15000;
    @AssertTrue(message = "rerank top-k must not exceed top-n")
    public boolean isWindowValid() { return topK <= topN; }
    public boolean isActive() { return enabled && !"off".equals(mode); }

    /** 是否为远端（硅基流动）重排：remote 模式需要 Authorization 头与 model 字段。 */
    public boolean isRemote() { return "remote".equals(mode); }
}
