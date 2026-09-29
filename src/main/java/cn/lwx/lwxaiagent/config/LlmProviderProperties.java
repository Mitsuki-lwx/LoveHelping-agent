package cn.lwx.lwxaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>统一 LLM provider 配置（ADR-58）</b>：声明在 {@code app.llm.providers} 下的列表，
 * <b>加一个 provider = 加一条配置</b>，不需要新写 Java 类。
 *
 * <p><b>为什么有这个类</b>：在它之前，每接一个 provider 都要新写一个配置类
 * （{@code BigModelLastResortConfig} / {@code ClineFallbackConfig}）甚至一个协议适配类
 * （{@code RestFallbackChatModel} / {@code ClineApiCompatConfig}）。
 * 那是把"供应商差异"编码进了 Java，接第 N 个供应商的成本是线性的。
 * 本类把它降为数据：列表顺序即链路顺序，<b>第一条 {@code enabled} 的是主链</b>，
 * 其余依次为降级级。</p>
 *
 * <p><b>覆盖范围</b>：所有 <b>OpenAI 兼容</b>（{@code /chat/completions} 形状）的供应商 ——
 * DeepSeek 官方 / cline 网关 / 硅基流动 / bigmodel / DashScope 兼容模式 均可用同一套描述。
 * 非 OpenAI 协议的供应商仍需专门的 {@code ChatModel} 实现，那是刻意的边界（见 ADR-58）。</p>
 *
 * <p><b>密钥纪律</b>：{@code api-key} 一律写 {@code ${ENV_VAR:}} 占位，真实值只留在
 * gitignored 的 {@code .env.local} / {@code target/classes/application-local.yml}
 * （AGENTS.md §3 + CI gitleaks）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.llm")
public class LlmProviderProperties {

    /**
     * provider 列表，<b>顺序即链路顺序</b>。第一条 {@code enabled=true} 的作为主链
     * （{@code @Primary ChatModel} 背后那个），其余作为降级级。
     */
    private List<Provider> providers = new ArrayList<>();

    /** 单个 provider 描述。 */
    @Data
    public static class Provider {
        /** 唯一名字：用作熔断器键与指标标签（{@code llm_call_total{provider=...}}）。重名会启动失败。 */
        private String name;

        /** 端点基址，<b>不含</b> {@code /chat/completions}（例：{@code https://api.deepseek.com}）。 */
        private String baseUrl;

        /** 凭据，写 {@code ${ENV:}} 占位。为空时该 provider 不注册（不阻断启动）。 */
        private String apiKey;

        /** 模型名（原样发给供应商）。 */
        private String model;

        /** 补全路径，默认 {@code /v1/chat/completions}。少数网关用别的形状（如 bigmodel 的 {@code /chat/completions}）。 */
        private String completionsPath = "/v1/chat/completions";

        /** 是否启用。默认 true；置 false 可保留配置但不注册（替换 provider 时不必删记录）。 */
        private boolean enabled = true;

        /**
         * 响应<b>信封</b>字段名（可选）。少数网关把非流式响应包成
         * {@code {"data":{choices...},"success":true}}，标准 OpenAI 客户端在顶层找不到
         * {@code choices}。填 {@code data} 即自动拆封（仅对非 SSE 响应生效，流式不受影响）。
         */
        private String responseEnvelope;

        /** 连接超时覆盖（毫秒）。留空则用 {@code app.llm.connect-timeout-ms}。 */
        private Long connectTimeoutMs;
    }

    /** 第一条启用的 provider = 主链。 */
    public Provider primary() {
        return providers.stream().filter(Provider::isEnabled).findFirst().orElse(null);
    }

    /** 其余启用的 provider = 降级级（顺序不变）。 */
    public List<Provider> fallbacks() {
        List<Provider> out = new ArrayList<>();
        boolean seenPrimary = false;
        for (Provider p : providers) {
            if (!p.isEnabled()) continue;
            if (!seenPrimary) {
                seenPrimary = true;
                continue;
            }
            out.add(p);
        }
        return out;
    }
}
