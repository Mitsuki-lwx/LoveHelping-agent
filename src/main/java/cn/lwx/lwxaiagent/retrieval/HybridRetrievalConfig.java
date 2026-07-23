package cn.lwx.lwxaiagent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * 混合检索 Spring 配置类。
 * <p>
 * 仅在 application.yml 中设置 {@code rag.type=hybrid} 时生效。
 * 创建 4 个 Bean：
 * 1. MilvusVectorRetriever — 向量语义检索
 * 2. ESKeywordRetriever — BM25 关键词检索
 * 3. RRFFuser — RRF 融合排序
 * 4. HybridRetrievalService — 统一入口
 * <p>
 * 默认模式 {@code rag.type=pgvector} 时，所有 @ConditionalOnProperty 都不会命中。
 * <p>
 * Schema 初始化（ES 索引、Milvus collection）由 {@link DatabaseSchemaInitializer} 统一管理，
 * 通过 @DependsOn 确保在检索器 Bean 之前完成。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class HybridRetrievalConfig {

    @Bean
    @DependsOn("databaseSchemaInitializer")
    public MilvusVectorRetriever milvusVectorRetriever(
            MilvusClientV2 milvusClient,
            HybridRetrievalProperties props,
            @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        var m = props.getHybrid().getMilvus();
        log.info("Creating MilvusVectorRetriever (collection={})", m.getCollectionName());
        return new MilvusVectorRetriever(milvusClient, m.getCollectionName(),
                m.getVectorDim(), embeddingModel);
    }

    @Bean
    @DependsOn("databaseSchemaInitializer")
    public ESKeywordRetriever esKeywordRetriever(
            ElasticsearchClient esClient,
            HybridRetrievalProperties props) {
        var es = props.getHybrid().getEs();
        log.info("Creating ESKeywordRetriever (index={})", es.getIndex());
        return new ESKeywordRetriever(esClient, es.getIndex());
    }

    @Bean
    public RRFFuser rrfFuser(HybridRetrievalProperties props) {
        var rrf = props.getHybrid().getRrf();
        return new RRFFuser(rrf.getK(), rrf.getTopK());
    }

    @Bean
    public HybridRetrievalService hybridRetrievalService(
            MilvusVectorRetriever milvusRetriever,
            ESKeywordRetriever esRetriever,
            RRFFuser rrfFuser) {
        return new HybridRetrievalService(milvusRetriever, esRetriever, rrfFuser);
    }
}
