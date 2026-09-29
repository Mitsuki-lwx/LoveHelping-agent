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
 * <p>容器里只有一个"业务可见的 {@code ChatModel}"——{@link LlmGateway}，
 * 由 {@code @Primary} 指定。<b>供应商级模型不再在这里声明</b>：
 * 自 ADR-58 起，主链与降级级全部由 {@code app.llm.providers} 配置驱动，
 * 由 {@link LlmProviderConfig} 装配成 {@code LlmProviderChain} 交给网关。</p>
 *
 * <p><b>为什么把供应商 bean 从这里移走</b>：此前每接一个供应商就要在本类新增一个
 * {@code @Bean}（外加可能的协议适配类）。接第 N 个供应商的成本是线性的，且"哪些级生效"
 * 同时取决于构建期开关与运行时总闸，极易配错。现在接供应商 = 在 yml 加一条。</p>
 *
 * <p>主聊天管道（{@code ChatExecutor} / {@code MemoryExtractor} 等注入
 * {@code @Primary ChatModel} 的消费者）自动获得重试、降级与 token 计量能力，
 * 消费者零改动，且<b>对链长完全无感知</b>——这是 ADR-23「网关是唯一重试所有者」的直接后果。</p>
 *
 * @author lwx
 * @see LlmGateway 多供应商 LLM 网关（重试 + 降级 + 计量）
 * @see LlmProviderConfig 由配置装配主链与降级级
 */
@Configuration
@EnableConfigurationProperties(LlmGatewayProperties.class)
public class ChatModelConfig {

    /**
     * 声明主 ChatModel Bean = LlmGateway。
     * {@code @Primary}：容器中有多个 ChatModel 时，优先注入本 Bean。
     *
     * @param llmGateway 多供应商网关（构造时已由 {@code LlmProviderChain} 注入主链与降级级）
     * @return 被标记为 Primary 的 ChatModel（默认模型 = LlmGateway）
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(LlmGateway llmGateway) {
        return llmGateway;
    }
}
