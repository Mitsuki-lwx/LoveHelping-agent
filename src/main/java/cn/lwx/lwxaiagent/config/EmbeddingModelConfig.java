package cn.lwx.lwxaiagent.config;

import cn.lwx.lwxaiagent.infrastructure.embedding.SiliconFlowEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 嵌入模型主 Bean 配置。
 *
 * <p><b>Phase 8 迁移（ADR-36）</b>：容器中现在有<b>两个</b> embedding bean——</p>
 * <ul>
 *   <li>{@code dashscopeEmbeddingModel}：DashScope 原生通道（旧，保留用于回滚）</li>
 *   <li>{@code siliconFlowEmbeddingModel}：硅基流动 OpenAI 兼容通道（新，默认）</li>
 * </ul>
 *
 * <p>{@code @Primary} 由 {@code app.rag.embedding.provider} 决定，取值：</p>
 * <ul>
 *   <li>{@code siliconflow}（默认）：主 = 硅基流动 Qwen3-Embedding-0.6B（1024 维）</li>
 *   <li>{@code dashscope}：主 = DashScope text-embedding-v3（回滚路径）</li>
 * </ul>
 *
 * <p><b>为什么用环境变量/配置而非直接删旧 bean</b>：迁移必须可一键回滚。
 * 删掉 DashScope bean 会让"硅基流动出问题"变成"必须改代码重新部署"。
 * 保留双 bean + 配置切换，回滚只需改一个环境变量并重启。</p>
 *
 * <p><b>注意</b>：{@code @Primary} 只解决"无 Qualifier 注入"的歧义。
 * 真正消费 embedding 的三个点（PgVectorVectorStore / ParentChildDocumentRetriever /
 * SkillIngestor）已改为注入 {@code @Primary}，从而自动跟随本配置切换。</p>
 *
 * @author lwx
 * @since Phase 8 (ADR-36)
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SiliconFlowProperties.class)
public class EmbeddingModelConfig {

    /**
     * 硅基流动 embedding bean（新通道）。
     *
     * <p>无条件注册（不做 {@code @ConditionalOnProperty}）：这样即使当前 provider=dashscope，
     * bean 也在容器里，便于运行时诊断与"配置切换即生效"，不必重启改代码。</p>
     */
    @Bean("siliconFlowEmbeddingModel")
    public EmbeddingModel siliconFlowEmbeddingModel(SiliconFlowProperties props, ObjectMapper objectMapper) {
        if (!props.hasApiKey()) {
            log.warn("app.siliconflow.api-key 未配置（环境变量 SF_API_KEY 为空）——"
                    + "若 app.rag.embedding.provider=siliconflow，启动后检索/写入将报错。"
                    + "回滚方式：设置 app.rag.embedding.provider=dashscope");
        } else {
            log.info("SiliconFlow embedding 已装配: model={}, baseUrl={}",
                    props.getEmbeddingModel(), props.getBaseUrl());
        }
        return new SiliconFlowEmbeddingModel(props, objectMapper);
    }

    /**
     * 声明主 EmbeddingModel。按 {@code app.rag.embedding.provider} 在两个实现间选择。
     *
     * @param provider 提供方标识，默认 {@code siliconflow}
     */
    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Value("${app.rag.embedding.provider:siliconflow}") String provider,
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashscopeEmbeddingModel,
            @Qualifier("siliconFlowEmbeddingModel") EmbeddingModel siliconFlowEmbeddingModel) {

        if ("dashscope".equalsIgnoreCase(provider)) {
            log.warn("embedding provider = dashscope（回滚模式）：使用 DashScope 原生通道");
            return dashscopeEmbeddingModel;
        }
        if (!"siliconflow".equalsIgnoreCase(provider)) {
            throw new IllegalArgumentException(
                    "未知的 app.rag.embedding.provider: '" + provider + "'，可选值 siliconflow | dashscope");
        }
        log.info("embedding provider = siliconflow：使用硅基流动 Qwen3-Embedding-0.6B（1024 维）");
        return siliconFlowEmbeddingModel;
    }
}
