package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * <p>
 * 知识条目实体类 —— 对应数据库表 <strong>knowledge_entry</strong>。
 * </p>
 *
 * <p>
 * <strong>注意：此类已废弃（@Deprecated），被 {@link cn.lwx.lwxaiagent.entity.EvolutionSkill} 取代。</strong>
 * 保留此实体仅用于读取旧版本遗留数据，新代码应使用 EvolutionSkill 体系。
 * 对应的 Mapper 接口 {@link cn.lwx.lwxaiagent.mapper.KnowledgeEntryMapper} 也已标记为 @Deprecated。
 * </p>
 *
 * <p>
 * <strong>核心作用（历史）：</strong>该类用于存储对话过程中 AI 提取的结构化知识条目，
 * 包括流程经验（PROCESS）、模式总结（PATTERN）、原则提炼（PRINCIPLE）和案例记录（CASE）四种类型。
 * </p>
 *
 * <p>
 * <strong>条目类型说明（{@code entryType} 字段）：</strong>
 * </p>
 * <ul>
 *   <li><strong>PROCESS：</strong>流程类经验，描述完成某项任务的具体步骤和流程。</li>
 *   <li><strong>PATTERN：</strong>模式类经验，总结某种通用的问题解决模式或设计模式。</li>
 *   <li><strong>PRINCIPLE：</strong>原则类经验，提炼出的指导性原则或最佳实践。</li>
 *   <li><strong>CASE：</strong>案例类记录，记录具体的问题案例及其解决方案。</li>
 * </ul>
 *
 * <p>
 * <strong>与 EvolutionSkill 的区别：</strong>EvolutionSkill 是重构后的新版本，
 * 增加了向量检索支持（通过 Milvus + ES），使用了更规范的字段命名和更清晰的数据结构。
 * KnowledgeEntry 是早期版本的实现，缺少向量存储集成。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.entity.EvolutionSkill
 * @see cn.lwx.lwxaiagent.mapper.KnowledgeEntryMapper
 * @deprecated 请使用 {@link cn.lwx.lwxaiagent.entity.EvolutionSkill} 替代
 */
@Data
@TableName("knowledge_entry")
public class KnowledgeEntry {

    /**
     * 主键 ID，数据库自增（AUTO_INCREMENT）。
     * <p>使用 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.annotation.IdType#AUTO} 策略，
     * 由数据库自动生成唯一标识。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，对应数据库列 {@code tenant_id}。
     * <p>用于多租户数据隔离，每条知识条目归属于一个特定的租户。</p>
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 来源会话 ID，对应数据库列 {@code source_session_id}。
     * <p>记录该知识条目是从哪一次对话会话中提取出来的，便于追溯知识的来源上下文。</p>
     */
    @TableField("source_session_id")
    private String sourceSessionId;

    /**
     * 条目类型，对应数据库列 {@code entry_type}。
     * <p>标识知识条目的分类类型，取值范围包括：</p>
     * <ul>
     *   <li>{@code "PROCESS"} —— 流程类经验</li>
     *   <li>{@code "PATTERN"} —— 模式类经验</li>
     *   <li>{@code "PRINCIPLE"} —— 原则类经验</li>
     *   <li>{@code "CASE"} —— 案例类记录</li>
     * </ul>
     * <p>不同类型的知识条目在检索和使用时有不同的处理逻辑。</p>
     */
    @TableField("entry_type")
    private String entryType;

    /**
     * 标签，对应数据库列 {@code label}。
     * <p>为知识条目打上的简短分类标签，用于快速筛选和分类。
     * 对于 CASE 类型的条目，标签可用于按业务领域分组检索（如"支付问题"、"登录异常"等）。</p>
     */
    private String label;

    /**
     * 标题，对应数据库列 {@code title}。
     * <p>知识条目的标题，简要概括该条目的核心内容，方便在列表中快速浏览和识别。</p>
     */
    private String title;

    /**
     * 知识内容，对应数据库列 {@code content}。
     * <p>知识条目的详细正文内容，包含完整的经验描述、操作步骤、注意事项等。
     * 这是被检索后注入到 LLM prompt 中的核心文本。</p>
     */
    private String content;

    /**
     * 标签列表，对应数据库列 {@code tags}。
     * <p>以字符串形式存储的多个标签，用于多维度的知识分类和检索过滤。
     * 通常使用逗号或其他分隔符连接多个标签值。</p>
     */
    private String tags;

    /**
     * 权重，对应数据库列 {@code weight}。
     * <p>知识条目的权重值，数值越大表示该条目越重要或越常用。
     * 用于在检索结果排序时影响排序优先级，权重高的条目排在前面。</p>
     */
    private Integer weight;

    /**
     * 质量评分，对应数据库列 {@code quality_score}。
     * <p>评估该知识条目的质量高低。通常基于用户反馈（点赞/点踩/投票）进行动态调整。
     * 高分条目更值得信赖，低分条目可能在检索时被过滤掉。</p>
     */
    @TableField("quality_score")
    private Integer qualityScore;

    /**
     * 是否启用（活跃状态），对应数据库列 {@code is_active}。
     * <p>{@code true} 表示该知识条目处于活跃状态，可被检索和使用；
     * {@code false} 表示已停用（如过期或质量不达标）。默认值为 {@code true}。</p>
     */
    @TableField("is_active")
    private Boolean isActive;

    /**
     * 创建时间，对应数据库列 {@code created_at}。
     * <p>记录该知识条目首次创建时的服务器时间戳。</p>
     */
    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    /**
     * 最后更新时间，对应数据库列 {@code updated_at}。
     * <p>记录该知识条目最后一次被修改的时间戳（如内容更新、评分变化等）。</p>
     */
    @TableField("updated_at")
    private java.time.LocalDateTime updatedAt;

    /**
     * 无参构造函数。
     * <p>MyBatis 和 JSON 序列化/反序列化框架（如 Jackson）需要无参构造函数来创建实例。</p>
     */
    public KnowledgeEntry() {}

    /**
     * 全参构造函数（不含 ID 和时间戳字段）。
     * <p>用于在代码中便捷地创建新的知识条目。ID 由数据库自增生成，
     * 时间戳由数据库自动维护，{@code isActive} 默认为 {@code true}。</p>
     *
     * @param tenantId       租户 ID，标识知识条目归属的租户
     * @param sourceSessionId 来源会话 ID，标识知识来自哪次对话
     * @param entryType      条目类型（PROCESS / PATTERN / PRINCIPLE / CASE）
     * @param label          分类标签，用于快速筛选
     * @param title          知识标题，简要概括内容
     * @param content        知识详细内容，检索命中后注入 LLM prompt
     * @param tags           多标签字符串，用于多维度分类
     * @param weight         权重值，影响检索排序优先级
     * @param qualityScore   初始质量评分
     */
    public KnowledgeEntry(String tenantId, String sourceSessionId, String entryType,
                          String label, String title, String content,
                          String tags, Integer weight, Integer qualityScore) {
        this.tenantId = tenantId;
        this.sourceSessionId = sourceSessionId;
        this.entryType = entryType;
        this.label = label;
        this.title = title;
        this.content = content;
        this.tags = tags;
        this.weight = weight;
        this.qualityScore = qualityScore;
        this.isActive = true;
    }
}
