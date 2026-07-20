package cn.lwx.lwxaiagent.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.type", havingValue = "hybrid")
public class HybridRetrievalConfig {

    /**
     * 创建 Milvus 检索器。
     * init() 中会连接 Milvus 并确保 collection 存在。
     */
    @Bean
    public MilvusVectorRetriever milvusVectorRetriever(
            HybridRetrievalProperties props, EmbeddingModel embeddingModel) {
        var m = props.getHybrid().getMilvus();
        log.info("Initializing Milvus retriever: {}:{}", m.getHost(), m.getPort());
        var retriever = new MilvusVectorRetriever(
                m.getHost(), m.getPort(), m.getCollectionName(),
                m.getVectorDim(), embeddingModel);
        retriever.init();
        return retriever;
    }

    /**
     * 创建 ES 关键词检索器。
     * init() 中会连接 ES 并创建 REST 客户端。
     */
    @Bean
    public ESKeywordRetriever esKeywordRetriever(HybridRetrievalProperties props) {
        //这是先得到配置中的 ES 相关信息，然后创建 ESKeywordRetriever 实例，并调用 init() 方法进行初始化。
        var es = props.getHybrid().getEs();
        log.info("Initializing ES retriever: {}", es.getUris());
        var retriever = new ESKeywordRetriever(es.getUris(), es.getIndex());//传入 ES 的 URI 和索引名称
        retriever.init();
        return retriever;
    }

    /**
     * 创建 RRF 融合排序器。
     * k 和 topK 参数从配置注入，可在运行时调整。
     */
    @Bean
    public RRFFuser rrfFuser(HybridRetrievalProperties props) {
        var rrf = props.getHybrid().getRrf();
        return new RRFFuser(rrf.getK(), rrf.getTopK());
    }

    /**
     * 创建混合检索服务（统一入口）。
     * 依赖三个 Bean：MilvusVectorRetriever + ESKeywordRetriever + RRFFuser。
     */
    @Bean
    public HybridRetrievalService hybridRetrievalService(
            MilvusVectorRetriever milvusRetriever,
            ESKeywordRetriever esRetriever,
            RRFFuser rrfFuser) {
        return new HybridRetrievalService(milvusRetriever, esRetriever, rrfFuser);
    }
}
