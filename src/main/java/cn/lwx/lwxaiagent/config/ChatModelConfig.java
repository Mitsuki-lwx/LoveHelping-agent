package cn.lwx.lwxaiagent.config;

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
 * <p><b>主模型 = {@link LlmGateway}</b>（ADR-7 建立；ADR-48 建三级降级链；<b>ADR-51 收成单级</b>）：</p>
 * <ul>
 *   <li><b>主</b>：DeepSeek 官方 {@code https://api.deepseek.com} + {@code deepseek-flash}
 *       （ADR-51 起；此前是 OpenRouter {@code stealth/space-bunny-alpha}）</li>
 *   <li><b>备一</b>：DashScope {@code qwen-plus}（本类注册）——
 *       ⛔ <b>ADR-51 起默认关闭</b>：域名 {@code dashscope.aliyuncs.com} 本机不可达，
 *       属"假备用"，见 {@link #deepSeekFallbackModel} 的说明</li>
 *   <li><b>备二（最低）</b>：bigmodel {@code glm-4-flash}
 *       （{@link BigModelLastResortConfig}）——⛔ ADR-51 起默认关闭：实测返回 400</li>
 * </ul>
 * <p>三级链的<b>机制</b>仍在（可开关式回滚），只是当前<b>没有配置任何降级目标</b>。
 * 网关的判据是 {@code degradeTiers()} 是否非空，故两级都关时行为退化为
 * 「主链 + 重试」，与 ADR-48 之前的单级语义一致。</p>
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
     * 降级链备一（2026-09-06 降级链落地；ADR-48 起为三级链的中间级）：
     * 此前 LlmGateway 的 @Qualifier("deepSeekChatModel") 引用的 bean 从未注册
     * （"deepseek" 非 Spring AI 标准 provider 名，配置被静默忽略）——
     * 降级链是纸面降级（fallback==null，故障时直接 5000）。现注册真实可用的备：
     * DashScope OpenAI 兼容端点 + qwen-plus（key 复用 spring.ai.dashscope.api-key）。
     *
     * <p>bean 名字沿用 {@code deepSeekChatModel} 是历史包袱——它实际是 DashScope qwen-plus，
     * 不是 DeepSeek。改名会牵动 LlmGateway 的 qualifier 与既有单测，收益不抵风险，
     * 故保留名字但在此写明真相（切勿照名字理解）。</p>
     *
     * <p>⛔ <b>ADR-51（2026-09-27）：本 bean 默认不再注册。</b>
     * {@code dashscope.aliyuncs.com} 在本机<b>不可达</b>——DNS 解析到 Clash fake-ip
     * （{@code 198.18.0.138} / {@code fdfe:dcba:9876::c5}），直连与走代理的 TLS 握手
     * 均被中断；Java 侧同形失败最早见于 2026-09-16
     * （{@code ResourceAccessException: ... Remote host terminated the handshake}）。
     * 也就是说这一级是<b>假备用</b>：它让链路看起来有三层，实际只有一层，
     * 却还要在每次主链故障时白等一次 TLS 超时。
     * 故加 {@code @ConditionalOnProperty} 开关式关闭——<b>不删类、不删注册逻辑</b>，
     * 域名恢复可达时把 {@code app.llm.fallback-enabled} 拨回 {@code true} 即可复活。</p>
     *
     * <p>关闭后 {@code LlmGateway} 的
     * {@code @Autowired(required=false) @Qualifier("deepSeekChatModel")} 拿到 {@code null}，
     * {@code degradeTiers()} 返回空列表，启动横幅也不再打印不存在的级别
     * ——「假端点比没端点更有害」（见 {@code LlmGateway#endpointBaseUrl}）。</p>
     */
    @Bean("deepSeekChatModel")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.llm.fallback-enabled", havingValue = "true")
    public ChatModel deepSeekFallbackModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.api-key:}") String dashScopeKey,
            LlmGatewayProperties props) {
        // 降级备模型（2026-09-06 落地）：spring-ai-alibaba 原生 DashScopeChatModel（qwen-plus）。
        // 原生通道经 WebClient 调用 dashscope /api/v1 —— 与 embedding 同域，验证过可用；
        // 而 OpenAI 兼容 /v1 型网关（dashscope compatible-mode / sensenova）对框架 WebClient 返回
        // 404（curl 同 URL 200，排查 header/body/UA 非因）——不用兼容通道作备，记录待办。
        return new RestFallbackChatModel(dashScopeKey, "qwen-plus", props.getConnectTimeoutMs(), props.getAttemptTimeoutMs());
    }
}
