package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import cn.lwx.lwxaiagent.retrieval.HybridRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>技能检索器 —— 在对话前检索相关经验技能并注入为提示词上下文</h1>
 *
 * <p><strong>核心作用：</strong>在每次用户发送消息时，从向量存储中检索与当前用户问题语义相关的
 * 已提炼技能（由 {@link SkillReflector} 从历史对话中提取），并将这些技能格式化为可供 LLM
 * 参考的知识片段，注入到系统提示词中。</p>
 *
 * <h2>检索策略（ADR-1 收敛后）</h2>
 * <ol>
 *   <li><b>语义检索：</b>将用户当前的输入消息作为查询文本，在向量存储中进行语义相似度搜索</li>
 *   <li><b>统一检索门面：</b>使用 {@link HybridRetrievalService}（后端为 pgvector，ADR-1），
 *       不可用时跳过技能注入（不影响对话）</li>
 *   <li><b>技能过滤：</b>从检索结果中筛选出 {@code source=evolution} 的技能文档，
 *       排除普通的知识库文档（由 {@code QuestionAnswerAdvisor} 处理）</li>
 *   <li><b>格式化输出：</b>将技能名称和内容组装为"【已学经验】"格式的提示词文本，直接注入 LLM 上下文</li>
 * </ol>
 *
 * <h2>搜索放大策略</h2>
 * <p>由于向量存储中同时存在进化技能和普通知识库文档，为提高命中技能的概率，
 * 实际搜索的 Top-K 值为配置值的 <b>5 倍</b>（{@code skillTopK * 5}），
 * 然后从这 5 倍的候选结果中筛选出真正的技能文档。</p>
 *
 * @see SkillReflector 技能反思器 —— 负责生产技能
 * @see HybridRetrievalService 统一检索门面（pgvector）
 * @see EvolutionProperties 进化系统配置属性 —— 提供 topK 等检索参数
 */
@Slf4j
@Component
public class SkillRetriever {

    /**
     * 统一检索服务（可选依赖，后端为 pgvector，ADR-1）。
     * 当未配置时，此字段为 null，技能检索功能不可用（跳过注入，不影响对话）。
     */
    @Autowired(required = false)
    private HybridRetrievalService hybridService;

    /**
     * 进化系统配置属性，提供技能检索的 Top-K 数量等参数
     */
    private final EvolutionProperties props;

    /**
     * 构造函数，通过构造器注入配置属性。
     *
     * @param props 进化系统配置属性对象
     */
    public SkillRetriever(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * <h3>检索相关技能并格式化为可注入的提示词文本</h3>
     *
     * <p>这是技能检索的主入口方法。根据用户当前输入，从向量存储中检索语义相关的历史经验技能，
     * 并将其格式化为"【已学经验】"格式的上下文文本，可直接拼接在系统提示词之后。</p>
     *
     * <h4>处理流程：</h4>
     * <ol>
     *   <li><b>功能开关检查：</b>如果 {@code evolution.enabled=false}，直接返回空字符串</li>
     *   <li><b>租户 ID 兜底：</b>如果 tenantId 为空，使用 "default" 作为默认租户</li>
     *   <li><b>扩大搜索范围：</b>以配置值 {@code skillTopK} 的 5 倍作为搜索数量，
     *       因为向量存储中混杂着技能文档和普通知识库文档</li>
     *   <li><b>执行检索：</b>调用 {@link #search} 方法执行实际检索</li>
     *   <li><b>技能筛选与格式化：</b>遍历检索结果，只保留包含有效 {@code skillName}
     *       和 {@code content} 元数据的文档（即进化技能文档），格式化为提示词文本</li>
     *   <li><b>空结果处理：</b>如果没有匹配的技能，返回空字符串（不影响正常对话流程）</li>
     * </ol>
     *
     * <h4>输出格式示例：</h4>
     * <pre>【已学经验】
     * - 先共情再建议: 当用户情绪激动地倾诉时，首先表达理解和共情，说"我能感受到你很痛苦"，
     *   等对方情绪平复后再给出具体建议...
     * - 避免主观评判: 不要直接说"你男朋友太渣了"，而是帮助用户客观分析双方的行为模式...</pre>
     *
     * @param userMessage 用户当前发送的消息文本，用作语义搜索的查询
     * @param tenantId    租户 ID，用于多租户数据隔离（可以为 null 或空白，此时默认为 "default"）
     * @return 格式化后的技能上下文文本，如果没有匹配结果则返回空字符串 ""
     */
    public String retrieveAsContext(String userMessage, String tenantId) {
        if (!props.isEnabled()) return "";
        if (tenantId == null || tenantId.isBlank()) tenantId = "default";

        // 搜索更多候选（可能混有知识库文档），从中挑选技能
        int searchTopK = props.getSkillTopK() * 5;
        List<Document> docs = search(userMessage, searchTopK, tenantId);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n【已学经验】\n");
        int count = 0;
        for (Document doc : docs) {
            String skillName = (String) doc.getMetadata().getOrDefault("skillName", "");
            String content = (String) doc.getMetadata().getOrDefault("content", "");

            if (!skillName.isBlank() && !content.isBlank()) {
                // 进化技能文档 —— 有 skillName 和 content 元数据
                sb.append("- ").append(skillName).append(": ").append(content).append("\n");
                count++;
            }
            // 跳过非技能文档（普通 RAG 知识库文档由 QuestionAnswerAdvisor 处理）
        }

        if (count == 0) {
            log.info("SkillRetriever found {} docs but no skill-format matches for tenant={}", docs.size(), tenantId);
            return "";
        }

        log.info("SkillRetriever injected {} skills for tenant={}", count, tenantId);
        return sb.toString();
    }

    /**
     * <h3>执行向量检索</h3>
     *
     * <p>按优先级依次尝试不同的检索服务，实现检索能力的优雅降级：</p>
     * <ol>
     *   <li><b>优先：</b>{@link HybridRetrievalService} —— 混合检索（向量 + 关键词 + RRF），
     *       召回率和准确率最高</li>
     *   <li><b>备用：</b>{@link MilvusVectorRetriever} —— 纯向量语义检索，覆盖混合服务不可用的场景</li>
     *   <li><b>兜底：</b>返回空列表，技能检索功能完全不可用时不影响正常对话流程</li>
     * </ol>
     *
     * @param query    用户输入的查询文本（即当前消息内容）
     * @param topK     返回的最大文档数量（已放大 5 倍，便于后续筛选技能文档）
     * @param tenantId 租户 ID，用于多租户数据隔离
     * @return 向量检索结果列表，检索服务不可用时返回空列表
     */
    private List<Document> search(String query, int topK, String tenantId) {
        if (hybridService != null) {
            log.info("SkillRetriever: searching vector store for '{}' (topK={})", query, topK);
            return hybridService.search(query, topK, tenantId);
        }
        log.debug("SkillRetriever: no vector store available, skipping skill search");
        return List.of();
    }
}
