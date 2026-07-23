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
 * 技能检索器 —— 对话前语义检索相关 skill 并格式化为 prompt 上下文。
 * <p>
 * 检索逻辑：
 * <ol>
 *   <li>用当前用户消息在向量库中语义搜索</li>
 *   <li>优先使用 HybridRetrievalService（Milvus + ES + RRF），
 *       不可用时降级为 Milvus only</li>
 *   <li>将命中文档中的 skill 信息格式化为 prompt 片段</li>
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
     * 检索相关 skill 并格式化为可注入 prompt 的文本。
     *
     * @param userMessage 用户当前消息
     * @param tenantId    租户 ID
     * @return 格式化的 skill 上下文，无结果时返回空字符串
     */
    public String retrieveAsContext(String userMessage, String tenantId) {
        if (!props.isEnabled()) return "";
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";

        // 多检索一些候选（与知识库混在一起可能被挤），从候选里挑 skill
        int searchTopK = props.getSkillTopK() * 5;
        List<Document> docs = search(userMessage, searchTopK, tenantId);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【已学经验】\n");
        int count = 0;
        for (Document doc : docs) {
            String skillName = (String) doc.getMetadata().getOrDefault("skillName", "");
            String content = (String) doc.getMetadata().getOrDefault("content", "");

            if (!skillName.isBlank() && !content.isBlank()) {
                // evolution skill 文档
                sb.append("- ").append(skillName).append(": ").append(content).append("\n");
                count++;
            }
            // 跳过非 skill 文档（普通 RAG 知识库文档由 QuestionAnswerAdvisor 处理）
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
