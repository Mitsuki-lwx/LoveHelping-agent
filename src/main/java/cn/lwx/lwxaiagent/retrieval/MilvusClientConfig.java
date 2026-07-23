package cn.lwx.lwxaiagent.retrieval;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端配置。
 * 将 MilvusClientV2 作为 Spring Bean 统一管理，
 * 供 {@link MilvusVectorRetriever} 和 {@link DatabaseSchemaInitializer} 共用。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class MilvusClientConfig {

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient(HybridRetrievalProperties props) {
        var m = props.getHybrid().getMilvus();
        log.info("Creating Milvus client: {}:{}", m.getHost(), m.getPort());
        return new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + m.getHost() + ":" + m.getPort())
                .build());
    }
}
