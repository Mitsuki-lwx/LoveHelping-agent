package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.KnowledgeVote;
import cn.lwx.lwxaiagent.mapper.KnowledgeVoteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <h1>进化服务 —— 用户反馈收集与知识进化</h1>
 * <p>
 * 本服务负责收集用户对 AI 回复的反馈（点赞/点踩/中立），并将反馈数据持久化到数据库。
 * 这些反馈数据是实现 AI"自我进化"的核心驱动力 —— 系统可以通过分析用户的反馈来持续优化
 * 提示词模板、技能检索策略和模型参数。
 * </p>
 *
 * <h2>进化机制</h2>
 * <p>
 * 受进化算法启发，系统将每一次用户反馈视为一次"自然选择"事件：
 * </p>
 * <ul>
 *   <li><b>LIKE（点赞）</b>：表示该回复质量高，对应的技能/模板获得正向"适应度"加分</li>
 *   <li><b>DISLIKE（点踩）</b>：表示该回复质量低，对应的技能/模板获得负向"适应度"减分</li>
 *   <li><b>NEUTRAL（中立）</b>：表示回复质量一般，不影响适应度评分</li>
 * </ul>
 * <p>
 * 累积的反馈数据可以用于：
 * <ul>
 *   <li>淘汰低质量的提示词模板（自然选择）</li>
 *   <li>强化高质量的技能配置（适者生存）</li>
 *   <li>识别用户偏好趋势（种群演化）</li>
 * </ul>
 * </p>
 *
 * <h2>数据去重策略</h2>
 * <p>
 * 以（租户 ID + 会话 ID + 消息索引）作为唯一键：
 * 如果同一个用户对同一条消息重复提交反馈，会更新（UPDATE）而非插入（INSERT）新记录，
 * 确保每条消息只有一条有效反馈记录。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see KnowledgeVote     反馈实体类
 * @see KnowledgeVoteMapper 反馈数据访问层
 */
@Slf4j
@Service
public class EvolutionService {

    /**
     * MyBatis-Plus 的 Mapper 接口，用于操作 knowledge_vote 表的 CRUD。
     * 继承自 BaseMapper，自动获得基本的增删改查能力。
     */
    private final KnowledgeVoteMapper voteMapper;

    /**
     * <h3>构造函数 - 依赖注入</h3>
     *
     * @param voteMapper MyBatis-Plus 反馈 Mapper，用于数据库操作
     */
    public EvolutionService(KnowledgeVoteMapper voteMapper) {
        this.voteMapper = voteMapper;
    }

    /**
     * <h3>提交用户反馈投票</h3>
     * <p>
     * 接收用户对某条 AI 回复的反馈，将其持久化到数据库。
     * 支持 INSERT（首次反馈）和 UPDATE（重复反馈）两种操作模式。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>若租户 ID 为空，使用默认值 "default"</li>
     *   <li>根据（租户 ID + 会话 ID + 消息索引）三元组查询是否已有反馈记录</li>
     *   <li>若存在则更新反馈类型和反馈文本（幂等操作，允许用户改变主意）</li>
     *   <li>若不存在则插入新的反馈记录</li>
     *   <li>记录操作日志</li>
     * </ol>
     *
     * <h4>事务保证</h4>
     * <p>
     * 使用 {@link Transactional @Transactional} 注解确保查询和写入的原子性，
     * 防止在高并发场景下产生重复记录。
     * </p>
     *
     * @param tenantId 租户 ID，用于多租户数据隔离。若为 {@code null}，自动使用 "default"
     * @param req      投票请求对象，包含会话 ID、消息索引、投票类型和可选反馈文本
     */
    @Transactional
    public void vote(String tenantId, VoteRequest req) {
        if (tenantId == null) tenantId = "default";
        String finalTenantId = tenantId;

        // 查询是否已存在同一条消息的反馈记录（幂等性保证）
        KnowledgeVote existing = voteMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeVote>()
                        .eq(KnowledgeVote::getTenantId, finalTenantId)
                        .eq(KnowledgeVote::getSessionId, req.sessionId)
                        .eq(KnowledgeVote::getMessageIndex, req.messageIndex));

        if (existing != null) {
            // 已有记录 → 更新（允许用户改变投票选择）
            existing.setVoteType(req.voteType);
            existing.setFeedbackText(req.feedbackText);
            voteMapper.updateById(existing);
        } else {
            // 新记录 → 插入
            KnowledgeVote vote = new KnowledgeVote(finalTenantId, req.sessionId, req.messageIndex, req.voteType);
            vote.setFeedbackText(req.feedbackText);
            voteMapper.insert(vote);
        }

        log.info("Vote: session={}, msg={}, type={}, feedback={}",
                req.sessionId, req.messageIndex, req.voteType, req.feedbackText);
    }

    /**
     * <h3>投票请求 DTO（Data Transfer Object）</h3>
     * <p>
     * 使用 Java 14+ 的 {@code record} 类型定义，自动生成：
     * <ul>
     *   <li>全参构造器</li>
     *   <li>各字段的访问器方法（如 {@code sessionId()}）</li>
     *   <li>{@code equals()}、{@code hashCode()} 和 {@code toString()} 方法</li>
     * </ul>
     * </p>
     *
     * <h4>字段校验</h4>
     * <p>
     * 使用 Jakarta Validation（{@code @NotBlank}、{@code @NotNull}、{@code @Pattern}）
     * 确保请求数据的合法性。校验失败时会抛出 {@link jakarta.validation.ConstraintViolationException}。
     * </p>
     *
     * @param sessionId    会话 ID，不能为空，用于关联具体的对话会话
     * @param messageIndex 消息索引，不能为空，标识会话中的第几条消息（从 0 开始）
     * @param voteType     投票类型，必须为 {@code LIKE}、{@code DISLIKE} 或 {@code NEUTRAL} 之一
     * @param feedbackText 可选的文本反馈，允许用户对点踩/点赞的原因进行补充说明
     */
    public record VoteRequest(
            @NotBlank(message = "sessionId 不能为空") String sessionId,
            @NotNull(message = "messageIndex 不能为空") Integer messageIndex,
            @NotBlank(message = "voteType 不能为空") @Pattern(regexp = "LIKE|DISLIKE|NEUTRAL", message = "voteType 必须为 LIKE/DISLIKE/NEUTRAL") String voteType,
            String feedbackText) {}
}
