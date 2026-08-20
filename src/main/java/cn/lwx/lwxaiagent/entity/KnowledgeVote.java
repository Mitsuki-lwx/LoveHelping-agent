package cn.lwx.lwxaiagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * <p>
 * 知识投票实体类 —— 对应数据库表 <strong>knowledge_vote</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该类用于记录用户对 AI 生成的知识内容（如进化技能、知识条目等）的反馈投票。
 * 用户可以在对话过程中对 AI 的回答进行点赞（UP）或点踩（DOWN），
 * 这些投票数据会用于后续知识质量评分的动态调整。
 * </p>
 *
 * <p>
 * <strong>业务流程：</strong>
 * </p>
 * <ol>
 *   <li>用户在对话界面对某条 AI 消息进行点赞或点踩操作。</li>
 *   <li>前端将投票信息（会话 ID、消息序号、投票类型等）发送到后端。</li>
 *   <li>后端创建一条 KnowledgeVote 记录保存到数据库。</li>
 *   <li>系统根据该会话的投票情况，定期或实时调整对应 EvolutionSkill 的 {@code qualityScore}。</li>
 *   <li>质量评分的变化反过来影响后续对话中的技能检索排序和过滤。</li>
 * </ol>
 *
 * <p>
 * <strong>投票类型说明（{@code voteType} 字段）：</strong>
 * </p>
 * <ul>
 *   <li><strong>UP：</strong>点赞，表示用户认可该知识的质量，对应知识评分上升。</li>
 *   <li><strong>DOWN：</strong>点踩，表示用户认为该知识质量不佳，对应知识评分下降。</li>
 * </ul>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.mapper.KnowledgeVoteMapper
 */
@Data
@TableName("knowledge_vote")
public class KnowledgeVote {

    /**
     * 主键 ID，数据库自增（AUTO_INCREMENT）。
     * <p>使用 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.annotation.IdType#AUTO} 策略，
     * 由数据库自动生成唯一标识。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID，对应数据库列 {@code tenant_id}。
     * <p>用于多租户数据隔离，每条投票记录归属于一个特定的租户。
     * 不同租户的投票数据互不可见，保证多租户场景下的数据安全。</p>
     */
    @TableField("tenant_id")
    private String tenantId;

    /**
     * 会话 ID，对应数据库列 {@code session_id}。
     * <p>标识该投票发生在哪一次对话会话中。在同一个会话中，用户可能对多条消息进行投票，
     * 通过组合 {@code sessionId} 和 {@code messageIndex} 可以唯一确定一条投票记录。</p>
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 消息序号（索引），对应数据库列 {@code message_index}。
     * <p>标识该投票针对的是对话中的第几条消息（从 0 或 1 开始计数）。
     * 结合 {@code sessionId} 可以精确定位到用户投票的具体消息位置。</p>
     */
    @TableField("message_index")
    private Integer messageIndex;

    /**
     * 投票类型，对应数据库列 {@code vote_type}。
     * <p>标识用户的投票方向，取值范围：</p>
     * <ul>
     *   <li>{@code "UP"} —— 点赞 / 正向反馈，表示用户认可该知识</li>
     *   <li>{@code "DOWN"} —— 点踩 / 负向反馈，表示用户不认可该知识</li>
     * </ul>
     * <p>此字段的值会影响对应知识条目的 {@code qualityScore}（质量评分）。</p>
     */
    @TableField("vote_type")
    private String voteType;

    /**
     * 反馈文本，对应数据库列 {@code feedback_text}。
     * <p>用户在投票时可选填的详细反馈意见。例如点踩时说明具体哪里不满意，
     * 点赞时补充说明该知识对自己有什么帮助。这些文本可用于人工审核或自动分析知识质量。</p>
     */
    @TableField("feedback_text")
    private String feedbackText;

    /**
     * 创建时间，对应数据库列 {@code created_at}。
     * <p>记录该投票记录的创建时间戳，用于按时间排序和分析投票趋势。</p>
     */
    @TableField("created_at")
    private java.time.LocalDateTime createdAt;

    /**
     * 无参构造函数。
     * <p>MyBatis 和 JSON 反序列化框架（如 Jackson）需要无参构造函数来实例化对象。</p>
     */
    public KnowledgeVote() {}

    /**
     * 简化构造函数（不含反馈文本和时间戳）。
     * <p>用于快速创建一条投票记录，适用于用户仅进行点赞/点踩操作而不填写详细反馈的场景。
     * ID 由数据库自增生成，{@code createdAt} 由数据库自动填充。</p>
     *
     * @param tenantId     租户 ID，标识投票归属的租户
     * @param sessionId    会话 ID，标识投票发生在哪次对话中
     * @param messageIndex 消息序号，标识投票针对对话中的第几条消息
     * @param voteType     投票类型（"UP" 点赞 / "DOWN" 点踩）
     */
    public KnowledgeVote(String tenantId, String sessionId, Integer messageIndex, String voteType) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.messageIndex = messageIndex;
        this.voteType = voteType;
    }
}
