package cn.lwx.lwxaiagent.retrieval;

import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF（Reciprocal Rank Fusion）融合排序器。
 * 将 Milvus（向量语义）和 ES（BM25 关键词）的搜索结果合并排序。
 * <p>
 * 核心公式：rrf_score(d) = Σ(1 / (k + rank_i(d)))
 * 其中 rank_i(d) 是文档 d 在第 i 个检索器中的排名，k 是平滑参数（默认 60）。
 * <p>
 * 为什么用 RRF 而非加权平均？
 * - RRF 不需要对分数做归一化（Milvus 出余弦相似度 0-1，ES 出 BM25 分数无上限）
 * - RRF 只依赖排名，对分数尺度不敏感
 * - 实现简单，效果好（业界已验证）
 * <p>
 * k 值的经验选择：
 * - Top 50 回来 → k=60 合理
 * - Top 20 回来 → k=30 效果更好
 */
public class RRFFuser {

    /** RRF 平滑参数 */
    private final int k;

    /** 最终返回给用户的结果数 */
    private final int topK;

    public RRFFuser(int k, int topK) {
        this.k = k;
        this.topK = topK;
    }

    /**
     * 融合两个检索器的结果。
     *
     * @param milvusResults Milvus 的向量搜索结果
     * @param esResults ES 的 BM25 搜索结果
     * @return 按 RRF 分数降序排列的 topK 条文档，每条 metadata 中包含 rrf_score
     */
    public List<Document> fuse(List<ScoredDocument> milvusResults,
                                List<ScoredDocument> esResults) {
        Map<String, Document> docMap = new HashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // 计算 Milvus 中每条文档的 RRF 贡献
        for (int i = 0; i < milvusResults.size(); i++) {
            ScoredDocument sd = milvusResults.get(i);
            String id = sd.getId();
            rrfScores.merge(id, 1.0 / (k + i), Double::sum);
            // putIfAbsent 保证先出现的检索器优先保留文档
            docMap.putIfAbsent(id, sd.getDocument());
        }

        // 计算 ES 中每条文档的 RRF 贡献
        for (int i = 0; i < esResults.size(); i++) {
            ScoredDocument sd = esResults.get(i);
            String id = sd.getId();
            rrfScores.merge(id, 1.0 / (k + i), Double::sum);
            docMap.putIfAbsent(id, sd.getDocument());
        }

        // 按 RRF 分数降序排列，取 topK
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    Document doc = docMap.get(e.getKey());
                    doc.getMetadata().put("rrf_score", String.valueOf(e.getValue()));
                    return doc;
                })
                .collect(Collectors.toList());
    }
}
