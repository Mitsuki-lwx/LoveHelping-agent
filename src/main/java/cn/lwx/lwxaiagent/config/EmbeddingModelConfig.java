package cn.lwx.lwxaiagent.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 嵌入模型主 Bean 配置。
 * <p>
 * DashScope 嵌入（dashscopeEmbeddingModel）暂留用于 RAG/记忆向量化（待调研开源嵌入后替换，ADR-14）；
 * 容器中同时存在 OpenAI 兼容的 openAiEmbeddingModel 时，显式将 DashScope 嵌入标记为
 * {@code @Primary}，避免无 Qualifier 注入（如 ReflectionScheduler）的歧义。
 * </p>
 */
@Configuration
public class EmbeddingModelConfig {

    @Bean
    @Primary
    public EmbeddingModel primaryEmbeddingModel(
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashscopeEmbeddingModel) {
        return dashscopeEmbeddingModel;
    }
}
