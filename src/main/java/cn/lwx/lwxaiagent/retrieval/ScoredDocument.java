package cn.lwx.lwxaiagent.retrieval;

import org.springframework.ai.document.Document;

/**
 * 带评分和 ID 的文档包装类。
 * <p>
 * Milvus 返回 {@code Document}（含 milvus_id 和 score 存在 metadata 中），
 * ES 返回 {@code ScoredDocument}（id 和 score 是顶层字段）。
 * RRF 融合时需要统一的 id + score + Document 结构，这个类就是做这个的。
 * <p>
 * 核心作用：给 RRF 融合排序算法提供统一的排名输入。
 */
public class ScoredDocument {

    /** 文档 ID（Milvus 用 milvus_id，ES 用 _id） */
    private final String id;

    /** Spring AI 文档对象，含 text 和 metadata */
    private final Document document;

    /** 原始相似度/BM25 分数 */
    private final double score;

    public ScoredDocument(String id, Document document, double score) {
        this.id = id;
        this.document = document;
        this.score = score;
    }

    public String getId() { return id; }
    public Document getDocument() { return document; }
    public double getScore() { return score; }
}
