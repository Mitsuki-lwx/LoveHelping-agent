package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import cn.lwx.lwxaiagent.retrieval.ESKeywordRetriever;
import cn.lwx.lwxaiagent.retrieval.HybridRetrievalService;
import cn.lwx.lwxaiagent.retrieval.MilvusVectorRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill Retriever -- semantically retrieves relevant skills before conversation and formats them as prompt context.
 * <p>
 * Retrieval logic:
 * <ol>
 *   <li>Semantically search the current user message in the vector store</li>
 *   <li>Prefer HybridRetrievalService (Milvus + ES + RRF),
 *       fallback to Milvus only if unavailable</li>
 *   <li>Format skill info from matched documents as prompt fragment</li>
 * </ol>
 */
@Slf4j
@Component
public class SkillRetriever {

    @Autowired(required = false)
    private HybridRetrievalService hybridService;

    @Autowired(required = false)
    private MilvusVectorRetriever milvusRetriever;

    @Autowired(required = false)
    private ESKeywordRetriever esRetriever;

    private final EvolutionProperties props;

    public SkillRetriever(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * Retrieve relevant skills and format as injectable prompt text.
     *
     * @param userMessage Current user message
     * @param tenantId    Tenant ID
     * @return Formatted skill context, empty string if no results
     */
    public String retrieveAsContext(String userMessage, String tenantId) {
        if (!props.isEnabled()) return "";
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";

        // Search more candidates (may be mixed with knowledge base), pick skill from candidates
        int searchTopK = props.getSkillTopK() * 5;
        List<Document> docs = search(userMessage, searchTopK, tenantId);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【已学经验】\n");
        int count = 0;
        for (Document doc : docs) {
            String skillName = (String) doc.getMetadata().getOrDefault("skillName", "");
            String content = (String) doc.getMetadata().getOrDefault("content", "");

            if (!skillName.isBlank() && !content.isBlank()) {
                // evolution skill documents
                sb.append("- ").append(skillName).append(": ").append(content).append("\n");
                count++;
            }
            // Skip non-skill documents (ordinary RAG knowledge base docs handled by QuestionAnswerAdvisor)
        }

        if (count == 0) {
            log.info("SkillRetriever found {} docs but no skill-format matches for tenant={}", docs.size(), tenantId);
            return "";
        }

        log.info("SkillRetriever injected {} skills for tenant={}", count, tenantId);
        return sb.toString();
    }

    private List<Document> search(String query, int topK, String tenantId) {
        if (hybridService != null) {
            log.info("SkillRetriever: searching hybrid for '{}' (topK={})", query, topK);
            return hybridService.search(query, topK, tenantId);
        }
        if (milvusRetriever != null) {
            log.info("SkillRetriever: searching Milvus for '{}' (topK={})", query, topK);
            return milvusRetriever.search(query, topK, tenantId);
        }
        log.debug("SkillRetriever: no vector store available, skipping skill search");
        return List.of();
    }
}
