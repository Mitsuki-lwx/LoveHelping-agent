package cn.lwx.lwxaiagent.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重排挂载点（ADR-15 阶段 4）：Spring AI 检索增强管线的 postretrieval 阶段。
 * <p>开启（enabled=true 且 mode=llm）时调用 {@link DocumentReranker} 把候选重排到 topK；
 * 关闭时原样透传（链路与现状完全一致）。指标：executions/candidates/fallback。</p>
 */
@Slf4j
@Component
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private final RerankProperties properties;
    private final DocumentReranker documentReranker;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public RerankDocumentPostProcessor(RerankProperties properties,
                                       DocumentReranker documentReranker,
                                       io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.properties = properties;
        this.documentReranker = documentReranker;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (!properties.isEnabled() || !"llm".equals(properties.getMode())) {
            return documents; // 关闭态：透传，零变化
        }
        if (documents == null || documents.size() <= properties.getTopK()) {
            return documents; // 退化：候选不足，不重排
        }
        try {
            meterRegistry.counter("rag.rerank.executions").increment();
            meterRegistry.counter("rag.rerank.candidates").increment(documents.size());
            List<Document> reranked = documentReranker.rerank(query.text(), documents, properties.getTopK());
            log.info("RAG rerank: {} -> {} candidates", documents.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            meterRegistry.counter("rag.rerank.fallback").increment();
            log.warn("RAG rerank failed, fallback to vector order: {}", e.getMessage());
            return documents;
        }
    }
}