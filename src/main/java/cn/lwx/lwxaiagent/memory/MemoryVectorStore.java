package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.retrieval.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 记忆向量存储（ADR-14 语义通道）：把脱敏摘要向量化进 pgvector，
 * 检索时按 user_id + source=memory 过滤，返回 top-k 最相关片段。
 */
@Slf4j
@Component
public class MemoryVectorStore {

    private final VectorStore vectorStore;
    private final HybridRetrievalService retrievalService;

    private static final String SOURCE_KEY = "source";
    private static final String SOURCE_MEMORY = "memory";
    private static final String USER_ID_KEY = "userId";

    public MemoryVectorStore(@Qualifier("PgVectorVectorStore") VectorStore vectorStore,
                             HybridRetrievalService retrievalService) {
        this.vectorStore = vectorStore;
        this.retrievalService = retrievalService;
    }

    /**
     * 将脱敏后的会话摘要写入向量存储。
     * metadata: source=memory, userId=xxx
     */
    public void addMemory(String summary, String userId, String conversationId) {
        if (summary == null || summary.isBlank()) return;
        try {
            Document doc = new Document(summary, Map.of(
                    SOURCE_KEY, SOURCE_MEMORY,
                    USER_ID_KEY, userId != null ? userId : "anonymous",
                    "conversationId", conversationId != null ? conversationId : ""
            ));
            vectorStore.add(List.of(doc));
            log.debug("Memory vector added: user={}, conv={}", userId, conversationId);
        } catch (Exception e) {
            log.warn("Memory vector add failed: {}", e.getMessage());
        }
    }

    /**
     * 按语义检索用户相关记忆片段（top-k）。
     * 结果中过滤 source=memory 的文档。
     */
    public List<Document> searchMemory(String query, String userId, int topK) {
        if (query == null || query.isBlank() || userId == null) return List.of();
        try {
            // 搜索所有文档，topK 放大以过滤出 memory 类型
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK * 3).build());
            return docs.stream()
                    .filter(doc -> {
                        Object src = doc.getMetadata().get(SOURCE_KEY);
                        Object uid = doc.getMetadata().get(USER_ID_KEY);
                        return SOURCE_MEMORY.equals(src) && userId.equals(uid);
                    })
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.warn("Memory vector search failed: {}", e.getMessage());
            return List.of();
        }
    }
}
