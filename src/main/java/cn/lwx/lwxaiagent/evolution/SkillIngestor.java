package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Skill Ingestor -- replaces KnowledgeIngestor.
 * <p>
 * Writes all extracted skills to both MySQL and vector stores (Milvus + ES).
 * In vector stores, text=description (for semantic search), metadata carries skillName and content.
 */
@Slf4j
@Component
public class SkillIngestor {

    @Resource
    private EvolutionSkillMapper skillMapper;

    @Autowired(required = false)
    private cn.lwx.lwxaiagent.retrieval.MilvusVectorRetriever milvusRetriever;

    @Autowired(required = false)
    private cn.lwx.lwxaiagent.retrieval.ESKeywordRetriever esRetriever;

    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private EmbeddingModel embeddingModel;

    @Transactional
    public void ingest(List<SkillReflector.SkillReflectionResult> results,
                       String tenantId, String sessionId) {
        for (var r : results) {
            EvolutionSkill skill = new EvolutionSkill(
                    tenantId, r.skillName(), r.description(),
                    r.content(), sessionId, r.qualityScore());
            skillMapper.insert(skill);

            // All skills go to vector store, using description for embedding
            ingestToVectorStore(skill);
        }
        log.info("Ingested {} skills from session {}", results.size(), sessionId);
    }

    private void ingestToVectorStore(EvolutionSkill skill) {
        // text=description for embedding, semantic search matches "when to use"
        Document doc = new Document(skill.getDescription(), Map.of(
                "skillName", skill.getSkillName() != null ? skill.getSkillName() : "",
                "content", skill.getContent() != null ? skill.getContent() : "",
                "source", "evolution",
                "skillId", String.valueOf(skill.getId()),
                "tenantId", skill.getTenantId() != null ? skill.getTenantId() : "default"));

        if (milvusRetriever != null) {
            try {
                milvusRetriever.storeDocument(doc);
            } catch (Exception e) {
                log.warn("Failed to store skill in Milvus: {}", e.getMessage());
            }
        }

        if (esRetriever != null) {
            try {
                esRetriever.indexDocument(doc);
            } catch (Exception e) {
                log.warn("Failed to store skill in ES: {}", e.getMessage());
            }
        }
    }
}
