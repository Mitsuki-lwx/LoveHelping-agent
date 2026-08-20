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

/**
 * <h1>技能摄取器 —— 将反思提取的技能持久化到多存储层</h1>
 *
 * <p><strong>核心作用：</strong>接收 {@link SkillReflector} 反思产生的技能列表，将它们同时写入
 * MySQL 数据库和向量存储系统（Milvus + Elasticsearch），实现技能的持久化与可检索化。</p>
 *
 * <h2>数据流向</h2>
 * <pre>
 * SkillReflector → SkillIngestor.ingest() → ┬→ MySQL (evolution_skill 表) —— 结构化存储
 *                                          ├→ Milvus (向量数据库)      —— 语义相似度搜索
 *                                          └→ Elasticsearch           —— 关键词搜索
 * </pre>
 *
 * <h2>向量存储策略</h2>
 * <ul>
 *   <li><b>嵌入文本：</b>使用技能的 {@code description}（描述）字段进行向量化，
 *       因为描述字段说明"何时使用该技能"，天然适合语义搜索匹配</li>
 *   <li><b>元数据：</b>在向量存储的 metadata 中携带 {@code skillName}、{@code content}、
 *       {@code skillId} 等完整信息，方便检索后获取完整内容</li>
 *   <li><b>容错：</b>Milvus 或 ES 存储失败仅输出 WARN 日志，不影响 MySQL 写入和其他存储的写入</li>
 * </ul>
 *
 * @see SkillReflector.SkillReflectionResult 反思结果记录
 * @see EvolutionSkill 技能实体类
 */
@Slf4j
@Component
public class SkillIngestor {

    /**
     * MyBatis Mapper，用于操作 MySQL 中的 evolution_skill 表
     */
    @Resource
    private EvolutionSkillMapper skillMapper;

    /**
     * pgvector 向量存储（ADR-1 收敛后技能向量的唯一落点）。
     * 显式按 Bean 名注入，与内存兜底 LoveAppVectorStore 区分。
     */
    @Resource
    @Qualifier("PgVectorVectorStore")
    private VectorStore vectorStore;

    /**
     * 嵌入模型（DashScope 百炼），用于将技能描述文本转换为向量。
     * 向量化后的数据存入 pgvector，支持后续的语义相似度搜索。
     */
    @Autowired
    @Qualifier("dashscopeEmbeddingModel")
    private EmbeddingModel embeddingModel;

    /**
     * <h3>批量摄取技能</h3>
     *
     * <p>将反思结果列表中的所有技能同时写入 MySQL 和向量存储。该方法具有事务性，
     * MySQL 写入失败时会回滚。</p>
     *
     * <h4>处理流程：</h4>
     * <ol>
     *   <li>遍历所有反思结果，为每个结果创建 {@link EvolutionSkill} 实体对象</li>
     *   <li>通过 {@code skillMapper.insert()} 将技能写入 MySQL 的 {@code evolution_skill} 表</li>
     *   <li>调用 {@link #ingestToVectorStore(EvolutionSkill)} 将技能同步写入 Milvus 和 ES</li>
     *   <li>全部处理完成后输出 INFO 级别的汇总日志</li>
     * </ol>
     *
     * @param results   反思结果列表，由 {@link SkillReflector#doReflect} 通过 LLM 调用生成
     * @param tenantId  租户 ID，用于多租户隔离
     * @param sessionId 来源会话 ID，关联到 {@code evolution_skill.source_session_id}，用于去重和溯源
     */
    @Transactional
    public void ingest(List<SkillReflector.SkillReflectionResult> results,
                       String tenantId, String sessionId) {
        for (var r : results) {
            EvolutionSkill skill = new EvolutionSkill(
                    tenantId, r.skillName(), r.description(),
                    r.content(), sessionId, r.qualityScore());
            skillMapper.insert(skill);

            // 所有技能都写入向量存储，使用 description（描述）做嵌入向量
            ingestToVectorStore(skill);
        }
        log.info("Ingested {} skills from session {}", results.size(), sessionId);
    }

    /**
     * <h3>将单个技能写入向量存储</h3>
     *
     * <p>创建一个 Spring AI {@link Document} 对象，将技能的描述文本作为待向量化的内容，
     * 携带完整的技能元数据（名称、内容、ID 等），分别写入 Milvus 和 Elasticsearch。</p>
     *
     * <h4>Document 结构：</h4>
     * <ul>
     *   <li><b>text（向量化文本）：</b>{@code skill.getDescription()} —— 技能的适用场景描述，
     *       用于后续语义搜索时的相似度匹配</li>
     *   <li><b>metadata（元数据）：</b>
     *     <ul>
     *       <li>{@code skillName} —— 技能名称（短标题，如"先共情再建议"）</li>
     *       <li>{@code content} —— 技能内容（详细的可操作指导文本）</li>
     *       <li>{@code source} —— 固定为 "evolution"，标记来源为进化系统</li>
     *       <li>{@code skillId} —— 技能在 MySQL 中的主键 ID，用于关联查询</li>
     *       <li>{@code tenantId} —— 租户 ID，用于多租户数据隔离</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <h4>容错策略：</h4>
     * <p>Milvus 或 Elasticsearch 写入失败时，仅输出 WARN 级别日志，不会抛出异常。
     * 这样设计是因为：</p>
     * <ul>
     *   <li>MySQL 已经成功写入，结构化数据不丢失</li>
     *   <li>向量存储作为增强搜索能力的手段，其不可用不应影响核心功能</li>
     *   <li>运维人员可以通过日志发现问题后进行修复和重试</li>
     * </ul>
     *
     * @param skill 已写入 MySQL 的技能实体（此时 {@code skill.getId()} 已有值）
     */
    private void ingestToVectorStore(EvolutionSkill skill) {
        // text=description 用于向量嵌入，语义搜索匹配"什么场景用"
        Document doc = new Document(skill.getDescription(), Map.of(
                "skillName", skill.getSkillName() != null ? skill.getSkillName() : "",
                "content", skill.getContent() != null ? skill.getContent() : "",
                "source", "evolution",
                "skillId", String.valueOf(skill.getId()),
                "tenantId", skill.getTenantId() != null ? skill.getTenantId() : "default"));

        // ADR-1 收敛后：技能统一写入 pgvector（原 Milvus + ES 双写已移除）
        try {
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.warn("Failed to store skill in vector store: {}", e.getMessage());
        }
    }
}
