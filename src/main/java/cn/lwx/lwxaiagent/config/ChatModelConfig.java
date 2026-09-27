package cn.lwx.lwxaiagent.config;

import cn.lwx.lwxaiagent.infrastructure.ai.LlmFallbackTier;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGateway;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGatewayProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * <h1>聊天模型（ChatModel）配置类</h1>
 *
 * <p>当容器中存在多个 ChatModel 实例时，通过 {@code @Primary} 指定默认注入目标。</p>
 *
 * <p><b>主模型 = {@link LlmGateway}</b>（ADR-7 建立；ADR-48 建三级降级链；
 * <b>ADR-51 关闭全部降级目标</b>；<b>ADR-52 把链泛化为"注册几个就有几级"</b>）：</p>
 * <ul>
 *   <li><b>主</b>：DeepSeek 官方 {@code https://api.deepseek.com} + {@code deepseek-flash}
 *       （ADR-51 起；此前是 OpenRouter {@code stealth/space-bunny-alpha}）</li>
 *   <li><b>降级级</b>：由 {@link LlmFallbackTier} bean 组成，按 {@code @Order} 排序。
 *       当前两个都<b>默认不注册</b>（ADR-51 实测均不可用）：
 *       <ul>
 *         <li>{@code @Order(10)} DashScope {@code qwen-plus}（本类）——
 *             域名本机不可达，属"假备用"</li>
 *         <li>{@code @Order(20)} bigmodel {@code glm-4-flash}
 *             （{@link BigModelLastResortConfig}）—— 实测返回 400</li>
 *       </ul></li>
 * </ul>
 * <p>降级链的<b>机制</b>仍在（可开关式恢复），只是当前<b>没有注册任何降级目标</b>。
 * 网关的判据是"链列表是否非空"，故零级时行为退化为「主链 + 重试」，
 * 与 ADR-48 之前的单级语义一致。</p>
 * <p>主聊天管道（LoveApp / MemoryExtractor 等注入 {@code @Primary ChatModel} 的消费者）
 * 自动获得重试、降级与 token 计量能力，消费者零改动，且**对链长完全无感知**——
 * 这是 ADR-23「网关是唯一重试所有者」的直接后果。</p>
 *
 * @author lwx
 * @see LlmGateway 多供应商 LLM 网关（重试 + 降级 + 计量）
 */
@Configuration
@EnableConfigurationProperties(LlmGatewayProperties.class)
public class ChatModelConfig {

    /**
     * 声明主 ChatModel Bean = LlmGateway。
     * {@code @Primary}：容器中有多个 ChatModel 时，优先注入本 Bean。
     *
     * @param llmGateway 多供应商网关（Spring 容器中的单例，构造时已注入主备模型）
     * @return 被标记为 Primary 的 ChatModel（默认模型 = LlmGateway）
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(LlmGateway llmGateway) {
        return llmGateway;
    }

    /**
     * 降级链备一（2026-09-06 降级链落地；ADR-48 起为三级链的中间级；<b>ADR-52 起为
     * {@link LlmFallbackTier} bean</b>）：DashScope 原生端点 + {@code qwen-plus}。
     *
     * <p>⛔ <b>ADR-51（2026-09-27）：本 bean 默认不注册。</b>
     * {@code dashscope.aliyuncs.com} 在本机<b>不可达</b>——DNS 解析到 Clash fake-ip
     * （{@code 198.18.0.138} / {@code fdfe:dcba:9876::c5}），直连与走代理的 TLS 握手
     * 均被中断；Java 侧同形失败最早见于 2026-09-16
     * （{@code ResourceAccessException: ... Remote host terminated the handshake}）。
     * 也就是说这一级是<b>假备用</b>：它让链路看起来有多层，实际只有一层，
     * 却还要在每次主链故障时白等一次 TLS 超时。
     * 故加 {@code @ConditionalOnProperty} 开关式关闭——<b>不删类、不删注册逻辑</b>，
     * 域名恢复可达时把 {@code app.llm.fallback-enabled} 拨回 {@code true} 即可复活。</p>
     *
     * <p><b>ADR-52 的两点变化</b>：
     * <ol>
     *   <li>bean 类型从 {@code ChatModel} 变为 {@link LlmFallbackTier}，
     *       网关经 {@code ObjectProvider} 收集 —— <b>加一级只需加一个 bean，不改网关签名</b>。
     *       顺带清掉历史包袱：原 bean 名 {@code deepSeekChatModel} 名不副实
     *       （它实际是 DashScope {@code qwen-plus}），现名 {@code dashScopeFallbackTier} 名副其实。</li>
     *   <li>{@code model} / {@code base-url} 改为可配置（原为 Java 字面量 / 常量），
     *       换供应商不必改代码 —— 这是 ADR-51 §已知限制"口子 3"的修法。</li>
     * </ol>
     * {@code @Order(10)} 决定它在链中的位置（bigmodel 兜底是 {@code @Order(20)}，排其后）。</p>
     *
     * <p>关闭后 {@code LlmGateway} 的 {@code ObjectProvider<LlmFallbackTier>} 收不到本 bean，
     * 链里没有这一级，启动横幅也不再打印不存在的级别
     * ——「假端点比没端点更有害」（见 {@code LlmGateway#endpointBaseUrl}）。</p>
     *
     * @param dashScopeKey DashScope 凭据（复用 {@code spring.ai.dashscope.api-key}）；
     *                     为空时本级仍会注册，但<b>真实调用时</b>抛
     *                     {@code IllegalStateException}（沿用"凭据在调用期校验"的既有约定）
     * @param model        模型名，可配（{@code app.llm.fallback.model}）
     * @param baseUrl      原生端点，可配（{@code app.llm.fallback.base-url}）；
     *                     同时作为"生效端点"指标的报告值
     * @param props        取超时预算（connect / attempt）
     */
    @Bean("dashScopeFallbackTier")
    @org.springframework.core.annotation.Order(10)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.llm.fallback-enabled", havingValue = "true")
    public LlmFallbackTier dashScopeFallbackTier(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.api-key:}") String dashScopeKey,
            @org.springframework.beans.factory.annotation.Value("${app.llm.fallback.model:qwen-plus}") String model,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.llm.fallback.base-url:" + RestFallbackChatModel.DEFAULT_ENDPOINT + "}") String baseUrl,
            LlmGatewayProperties props) {
        // 原生通道经 HttpClient 调用 dashscope /api/v1 —— 与 embedding 同域，历史上验证过可用；
        // 而 OpenAI 兼容 /v1 型网关（dashscope compatible-mode / sensenova）对框架 WebClient 返回
        // 404（curl 同 URL 200，排查 header/body/UA 非因）——不用兼容通道作备，记录待办。
        return new LlmFallbackTier("fallback",
                new RestFallbackChatModel(dashScopeKey, model, baseUrl,
                        props.getConnectTimeoutMs(), props.getAttemptTimeoutMs()),
                baseUrl);
    }
}
