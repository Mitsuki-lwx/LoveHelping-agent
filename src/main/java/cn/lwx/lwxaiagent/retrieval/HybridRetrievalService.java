package cn.lwx.lwxaiagent.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索统一入口服务。
 * 协调 Milvus（向量语义）和 ES（BM25 关键词）两个检索器，
 * 对结果做 RRF 融合排序后返回。
 * <p>
 * 调用链路：KnowledgeSearchTool → HybridRetrievalService → Milvus + ES → RRF → Top K
 * <p>
 * 每个通道多取一倍结果（userTopK * 2），给 RRF 提供更多候选文档。
 */
@Slf4j
public class HybridRetrievalService {

    private final MilvusVectorRetriever milvusRetriever;
    private final ESKeywordRetriever esRetriever;
    private final RRFFuser rrfFuser;

    public HybridRetrievalService(MilvusVectorRetriever milvusRetriever,
                                   ESKeywordRetriever esRetriever,
                                   RRFFuser rrfFuser) {
        this.milvusRetriever = milvusRetriever;
        this.esRetriever = esRetriever;
        this.rrfFuser = rrfFuser;
    }

    /**
     * 混合搜索入口，支持按 tenantId 过滤。
     *
     * @param query    用户查询文本
     * @param userTopK 用户期望返回的结果数
     * @param tenantId 租户 ID（null 时不过滤）
     */
    public List<Document> search(String query, int userTopK, String tenantId) {
        int channelN = userTopK * 2;

        List<Document> milvusDocs = milvusRetriever.search(query, channelN, tenantId);
        List<ScoredDocument> milvusResults = new ArrayList<>();
        //这里将 Milvus 返回的 Document 列表转换为 ScoredDocument 列表，解析 metadata 中的 milvus_id 和 score。
        for (Document doc : milvusDocs) {
            // 解析 metadata 中的 milvus_id 和 score，就是从 metadata 中取出 milvus_id 和 score 字段，构造 ScoredDocument。
            String id = (String) doc.getMetadata().getOrDefault("milvus_id", "");// milvus_id 是 Milvus 自动生成的唯一 ID
            String scoreStr = (String) doc.getMetadata().getOrDefault("score", "0");// score 是 Milvus 返回的相似度分数，字符串形式
            double score = 0;
            try { score = Double.parseDouble(scoreStr); } catch (Exception ignored) {}
            milvusResults.add(new ScoredDocument(id, doc, score));
        }

        // ES: 直接返回 ScoredDocument[]
        List<ScoredDocument> esResults = esRetriever.search(query, channelN, tenantId);

        log.debug("Hybrid search: milvus={}, es={}, rrfTopK={}",
                milvusResults.size(), esResults.size(), userTopK);

        return rrfFuser.fuse(milvusResults, esResults);
    }
}
