package cn.lwx.lwxaiagent.rag.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索重排器接口（ADR-15 阶段 4，可插拔）。
 * <p>输入 query 与候选文档（粗召回），返回重排后的前 K 条。
 * v1 为 {@link LlmDocumentReranker}（主线 LLM 打分）；未来 bge-reranker
 * 本地模型/API 实现本接口即可替换，消费者（RerankDocumentPostProcessor）零改动。</p>
 */
public interface DocumentReranker {

    /**
     * 重排：对候选打分并返回前 topK 条（保序）。
     *
     * @param query      用户查询
     * @param candidates 粗召回候选（已按序列定序；长度不足 topK 时原样返回）
     * @param topK       期望返回条数
     */
    List<Document> rerank(String query, List<Document> candidates, int topK);
}