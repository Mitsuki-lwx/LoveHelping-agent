package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.rag.rerank.RerankDocumentPostProcessor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG advisor 装配（ADR-15，Task 7 补齐历史 gap）。
 * <p>把已有积木真正接成"检索增强生成"管线：
 * {@link ParentChildDocumentRetriever}（方法论文档父子索引检索，small-to-large）
 * + {@link QueryRewriter}（按 {@code app.rag.query-rewrite.enabled} 开关启用）+ 上下文增强器
 * + 重排（ADR-15 阶段 4，rerank.enabled 时经 postretrieval 阶段生效，关闭态透传）。</p>
 * <p>此前该 advisor 从未装配：QueryRewriter/ParentChildDocumentRetriever 均已实现但无消费者。</p>
 */
@Configuration
public class RagAdvisorConfig {

    private final boolean queryRewriteEnabled;

    public RagAdvisorConfig(@Value("${app.rag.query-rewrite.enabled:false}") boolean queryRewriteEnabled) {
        this.queryRewriteEnabled = queryRewriteEnabled;
    }

    /**
     * 检索增强 advisor。未注入到全局 ChatClient 默认链，而是由需要 RAG 的节点（普通/沙盘）
     * 在 prompt 层面按需挂载——简单问题节点不检索（ADR-19 CAP-2）。
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            ParentChildDocumentRetriever documentRetriever,
            RerankDocumentPostProcessor rerankDocumentPostProcessor,
            ObjectProvider<QueryRewriter> queryRewriter) {
        RetrievalAugmentationAdvisor.Builder builder = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().build())
                .documentPostProcessors(rerankDocumentPostProcessor); // 重排：关闭态原样透传
        // 查询改写默认开（application.yml app.rag.query-rewrite.enabled=true）
        if (queryRewriteEnabled) {
            QueryRewriter rewriter = queryRewriter.getIfAvailable();
            if (rewriter != null) {
                builder.queryTransformers(rewriter);
            }
        }
        return builder.build();
    }
}