package cn.lwx.lwxaiagent.evolution;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class KnowledgeIngestor {

    @Resource
    private KnowledgeEntryMapper entryMapper;

    @Autowired(required = false)
    private cn.lwx.lwxaiagent.retrieval.MilvusVectorRetriever milvusRetriever;

    @Autowired(required = false)
    private cn.lwx.lwxaiagent.retrieval.ESKeywordRetriever esRetriever;

    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private EmbeddingModel embeddingModel;

    public void ingest(List<ConversationExtractor.ExtractedEntry> entries,
                       String tenantId, String sessionId) {
        for (var entry : entries) {
            KnowledgeEntry ke = new KnowledgeEntry(
                    tenantId, sessionId, entry.entryType(),
                    entry.label(), entry.title(), entry.content(),
                    entry.tags(), 1, entry.qualityScore());
            entryMapper.insert(ke);

            if ("CASE".equals(entry.entryType())) {
                ingestToVectorStore(ke);
            }
        }
        log.info("Ingested {} entries from session {}", entries.size(), sessionId);
    }

    private void ingestToVectorStore(KnowledgeEntry entry) {
        Document doc = new Document(entry.getContent(), Map.of(
                "title", entry.getTitle() != null ? entry.getTitle() : "",
                "tags", entry.getTags() != null ? entry.getTags() : "",
                "label", entry.getLabel() != null ? entry.getLabel() : "",
                "source", "evolution",
                "knowledge_entry_id", String.valueOf(entry.getId()),
                "tenantId", entry.getTenantId()));

        if (milvusRetriever != null) {
            try {
                milvusRetriever.storeDocument(doc);
            } catch (Exception e) {
                log.warn("Failed to store in Milvus: {}", e.getMessage());
            }
        }

        if (esRetriever != null) {
            try {
                esRetriever.indexDocument(doc);
            } catch (Exception e) {
                log.warn("Failed to store in ES: {}", e.getMessage());
            }
        }
    }
}
