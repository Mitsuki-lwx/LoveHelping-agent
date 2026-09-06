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
 * <p><b>主模型 = {@link LlmGateway}</b>（ADR-7）：装饰器包装主备两个模型——</p>
 * <ul>
 *   <li><b>主</b>：GoPlan（OpenAI 兼容端点，deepseek-v4-flash）</li>
 *   <li><b>备</b>：DeepSeek 官方（同模型双供应商容灾）</li>
 * </ul>
 * <p>主聊天管道（LoveApp / MemoryExtractor 等注入 {@code @Primary ChatModel} 的消费者）
 * 自动获得重试、降级与 token 计量能力，消费者零改动。</p>
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
     * 备模型注册（2026-09-06 降级链落地）：此前 LlmGateway 的 @Qualifier("deepSeekChatModel")
     * 引用的 bean 从未注册（"deepseek" 非 Spring AI 标准 provider 名，配置被静默忽略）——
     * 降级链是纸面降级（fallback==null，故障时直接 5000）。现注册真实可用的备：
     * DashScope OpenAI 兼容端点 + qwen-plus（key 复用 spring.ai.dashscope.api-key）。
     * 主（BigModel glm-4-flash）故障时 LlmGateway 自动切到本备。
     */
    @Bean("deepSeekChatModel")
    public ChatModel deepSeekFallbackModel(
            @org.springframework.beans.factory.annotation.Value("${spring.ai.dashscope.api-key:}") String dashScopeKey) {
        // 降级备模型（2026-09-06 落地）：spring-ai-alibaba 原生 DashScopeChatModel（qwen-plus）。
        // 原生通道经 WebClient 调用 dashscope /api/v1 —— 与 embedding 同域，验证过可用；
        // 而 OpenAI 兼容 /v1 型网关（dashscope compatible-mode / sensenova）对框架 WebClient 返回
        // 404（curl 同 URL 200，排查 header/body/UA 非因）——不用兼容通道作备，记录待办。
        return new RestFallbackChatModel(dashScopeKey, "qwen-plus");
    }
}
