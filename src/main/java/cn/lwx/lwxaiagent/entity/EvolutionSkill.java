package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * <p>
 * 进化技能实体类 —— 对应数据库表 <strong>evolution_skill</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该类用于存储从对话反思（Conversation Reflection）中提取出的可复用经验。
 * 系统会在每次对话结束后进行反思总结，将其中有价值的经验、教训、技巧等封装为一条"进化技能"记录，
 * 持久化到 MySQL 数据库中，供后续对话做语义检索和提示注入。
 * </p>
 *
 * <p>
 * <strong>存储架构：</strong>
 * </p>
 * <ul>
 *   <li><strong>MySQL：</strong>存储技能的全部结构化字段（名称、内容、质量评分等），用于管理和查询。</li>
 *   <li><strong>向量数据库（Milvus + ES）：</strong>使用 {@code description} 字段生成 embedding 向量，
 *       写入向量存储（Milvus）和 Elasticsearch 索引中，用于后续对话中的语义相似度检索。</li>
 * </ul>
 *
 * <p>
 * <strong>业务场景：</strong>
 * </p>
 * <ol>
 *   <li>对话结束后，反思模块从对话历史中提取经验，创建 EvolutionSkill 记录。</li>
 *   <li>新对话开始时，系统根据用户输入进行语义检索，匹配相关的 EvolutionSkill。</li>
 *   <li>匹配到的技能的 {@code content} 字段会被注入到 LLM 的 system prompt 中，指导 AI 行为。</li>
 *   <li>根据用户反馈更新 {@code qualityScore}，低质量技能会被标记为不活跃（{@code isActive = false}）。</li>
 * </ol>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.mapper.EvolutionSkillMapper
 */
@Data
@TableName("evolution_skill")
public class EvolutionSkill {

    /**
     * 主键 ID，数据库自增（AUTO_INCREMENT）。
     * <p>使用 MyBatis-Plus 的 {@link IdType#AUTO} 策略，由数据库自动生成。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，对应数据库列 {@code tenant_id}。
     * <p>用于多租户隔离，不同租户的技能数据互不可见。每条技能记录归属于一个特定的租户。</p>
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 技能名称，对应数据库列 {@code skill_name}。
     * <p>简洁描述该技能的名称，方便人工管理和识别，例如"处理用户抱怨的技巧"、"代码审查最佳实践"等。</p>
     */
    @TableField("skill_name")
    private String skillName;

    /**
     * 技能描述，对应数据库列 {@code description}。
     * <p><strong>重要：</strong>该字段被用作向量检索的 embedding 文本来源。系统会对此字段内容进行
     * 向量化处理（embedding），写入 Milvus 向量数据库和 Elasticsearch 索引。当新对话需要检索相关
     * 技能时，会用用户输入的 embedding 与此字段生成的 embedding 做语义相似度匹配。</p>
     * <p>因此，该字段应包含清晰、具体的场景描述，例如"当用户在代码审查中提出性能优化建议时使用此技能"。</p>
     */
    private String description;

    /**
     * 技能的具体经验内容，对应数据库列 {@code content}。
     * <p><strong>重要：</strong>当该技能被语义检索命中后，此字段的内容会被注入到 LLM 的 system prompt
     * （系统提示词）中，直接指导 AI 在当前对话中的行为。</p>
     * <p>内容应为具体的、可操作的经验总结，例如分步骤的操作指南、注意事项列表、常见陷阱提醒等。</p>
     */
    private String content;

    /**
     * 来源会话 ID，对应数据库列 {@code source_session_id}。
     * <p>记录该技能是从哪一次对话中提取出来的，用于追溯经验的来源，
     * 也用于去重判断——同一会话中不会重复提取相同的技能。</p>
     */
    @TableField("source_session_id")
    private String sourceSessionId;

    /**
     * 质量评分，对应数据库列 {@code quality_score}。
     * <p>用于评估该技能的质量和可信度。评分越高表示该技能越有价值。
     * 通常根据用户后续的反馈（点赞/点踩）动态调整。检索时可以设置最低评分阈值来过滤低质量技能。</p>
     */
    @TableField("quality_score")
    private Integer qualityScore;

    /**
     * 审核状态：PENDING（默认）/ APPROVED / REJECTED。
     * 萃取后设 PENDING，审核通过后设 APPROVED 才可被检索和向量化。
     */
    @TableField("audit_status")
    private String auditStatus;

    /**
     * 是否启用（活跃状态），对应数据库列 {@code is_active}。
     * <p>{@code true} 表示该技能处于活跃状态，可以被检索和使用；
     * {@code false} 表示该技能已停用（如质量过低或过期），不会被检索到。
     * 默认值为 {@code true}。</p>
     */
    @TableField("is_active")
    private Boolean isActive;

    /**
     * 创建时间，对应数据库列 {@code created_at}。
     * <p>记录该技能首次创建的时间戳。</p>
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 最后更新时间，对应数据库列 {@code updated_at}。
     * <p>记录该技能最后一次被修改的时间戳（如质量评分更新、内容修正等）。</p>
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 无参构造函数。
     * <p>MyBatis 和 JSON 反序列化（如 Jackson）需要无参构造函数来实例化对象。</p>
     */
    public EvolutionSkill() {}

    /**
     * 全参构造函数（不含 ID 和时间戳字段）。
     * <p>用于在代码中方便地创建新的进化技能记录。ID 由数据库自增生成，
     * 创建/更新时间由数据库自动填充，{@code isActive} 默认为 {@code true}。</p>
     *
     * @param tenantId       租户 ID，标识技能所属的租户
     * @param skillName      技能名称，简洁标识技能的标题
     * @param description    技能描述，用作向量检索的 embedding 文本
     * @param content        技能的具体经验内容，被检索到时注入 LLM prompt
     * @param sourceSessionId 来源会话 ID，追溯该技能来自哪次对话
     * @param qualityScore   初始质量评分
     */
    public EvolutionSkill(String tenantId, String skillName, String description,
                          String content, String sourceSessionId, Integer qualityScore) {
        this.tenantId = tenantId;
        this.skillName = skillName;
        this.description = description;
        this.content = content;
        this.sourceSessionId = sourceSessionId;
        this.qualityScore = qualityScore;
        this.isActive = true;
        this.auditStatus = "PENDING";
    }
}
