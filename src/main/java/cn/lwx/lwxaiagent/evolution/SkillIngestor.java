package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.entity.EvolutionSkill;
import cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class SkillIngestor {

    @Resource
    private EvolutionSkillMapper skillMapper;

    @Resource
    @Qualifier("PgVectorVectorStore")
    private VectorStore vectorStore;

    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private EmbeddingModel embeddingModel;

    /**
     * 摄取技能到 MySQL（审核状态 PENDING，不自动向量化）。
     * PII 脱敏 + 注入防护已由 SkillReflector 在调用前完成。
     */
    @Transactional
    public void ingest(List<SkillReflector.SkillReflectionResult> results,
                       String tenantId, String sessionId) {
        for (var r : results) {
            // PII 脱敏（二次保险：SkillReflector 已约束 LLM 用"对象/对方"，此处规则替换）
            String desensitizedContent = cn.lwx.lwxaiagent.memory.Desensitizer.mask(r.content());
            String desensitizedDesc = cn.lwx.lwxaiagent.memory.Desensitizer.mask(r.description());

            EvolutionSkill skill = new EvolutionSkill(
                    tenantId, r.skillName(),
                    desensitizedDesc, desensitizedContent,
                    sessionId, r.qualityScore());
            skill.setAuditStatus("PENDING");
            skillMapper.insert(skill);
            // 审核通过后才向量化（审核端点负责 vectorizeAndApprove）
        }
        log.info("Ingested {} skills (audit=PENDING) from session {}", results.size(), sessionId);
    }

    /**
     * 审核通过后向量化（由 AuditService 调用）。
     */
    public void vectorize(EvolutionSkill skill) {
        Document doc = new Document(skill.getDescription() != null ? skill.getDescription() : "", Map.of(
                "skillName", skill.getSkillName() != null ? skill.getSkillName() : "",
                "content", skill.getContent() != null ? skill.getContent() : "",
                "source", "evolution",
                "skillId", String.valueOf(skill.getId()),
                "tenantId", skill.getTenantId() != null ? skill.getTenantId() : "default"));
        try {
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.warn("Failed to store skill in vector store: {}", e.getMessage());
        }
    }
}
