package cn.lwx.lwxaiagent.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h2>统一检索门面服务（ADR-1 收敛后：后端为 pgvector）</h2>
 *
 * <p>检索栈收敛后（原 Milvus+ES+RRF 混合检索已移除），本类保持对外签名不变，
 * 内部改为单一 pgvector 向量检索。调用方（KnowledgeSearchTool / SkillRetriever）
 * 无需感知实现差异。</p>
 *
 * <h3>调用链路</h3>
 * <pre>
 * KnowledgeSearchTool / SkillRetriever
 *   |
 *   v
 * HybridRetrievalService.search(query, topK, tenantId)   ← 本类
 *   |
 *   v
 * PgVectorVectorStore.similaritySearch(query, topK)      ← pgvector 余弦相似度
 * </pre>
 *
 * <h3>租户过滤说明</h3>
 * <p>单租户期（ADR-13）知识库为全站共享，{@code tenantId} 参数保留以兼容旧签名，
 * 但不参与过滤；多租户重启后在此处追加 {@code tenantId} 元数据过滤。</p>
 *
 * @author lwx
 * @since 1.0
 * @see org.springframework.ai.vectorstore.pgvector.PgVectorStore
 */
@Slf4j
@Component
public class HybridRetrievalService {

    /**
     * pgvector 向量存储（构造器注入）。
     * 显式按 Bean 名 {@code PgVectorVectorStore} 注入，与内存兜底 {@code LoveAppVectorStore} 区分。
     */
    private final VectorStore vectorStore;

    public HybridRetrievalService(@Qualifier("PgVectorVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * <h3>检索入口</h3>
     *
     * @param query    用户输入的查询文本
     * @param userTopK 返回结果数量上限
     * @param tenantId 租户 ID（单租户期不使用，保留签名兼容，ADR-13）
     * @return 按相似度降序的文档列表，无结果时返回空列表
     */
    public List<Document> search(String query, int userTopK, String tenantId) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(userTopK).build());
    }
}
