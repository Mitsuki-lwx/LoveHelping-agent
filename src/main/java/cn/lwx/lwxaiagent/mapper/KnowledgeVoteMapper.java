package cn.lwx.lwxaiagent.mapper;

import cn.lwx.lwxaiagent.entity.KnowledgeVote;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 知识投票 Mapper 接口 —— 对应数据库表 <strong>knowledge_vote</strong>。
 * </p>
 *
 * <p>
 * <strong>核心作用：</strong>该接口是 MyBatis 的数据访问层（DAO），负责 {@link KnowledgeVote} 实体
 * 与数据库表 {@code knowledge_vote} 之间的映射和 SQL 操作。
 * 用于记录和查询用户对 AI 生成知识内容的投票反馈（点赞/点踩）。
 * </p>
 *
 * <p>
 * <strong>业务场景：</strong>
 * </p>
 * <ol>
 *   <li>用户在对话界面对 AI 的某条消息进行点赞或点踩操作。</li>
 *   <li>后端通过此 Mapper 将投票记录（会话 ID、消息序号、投票类型等）插入数据库。</li>
 *   <li>反思模块通过 {@link #findBySessionId(String)} 查询某次会话的全部投票记录。</li>
 *   <li>根据投票记录调整对应 EvolutionSkill 的 {@code qualityScore}（质量评分）。</li>
 * </ol>
 *
 * <p>
 * <strong>继承关系：</strong>继承 MyBatis-Plus 的 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper}，
 * 自动获得了通用的 CRUD（增删改查）方法，包括插入投票记录、更新反馈文本、删除记录等。
 * </p>
 *
 * @author lwx
 * @see cn.lwx.lwxaiagent.entity.KnowledgeVote
 */
@Mapper
public interface KnowledgeVoteMapper extends BaseMapper<KnowledgeVote> {

    /**
     * 根据会话 ID 查询该次对话的全部投票记录，按消息序号升序排列。
     * <p>用于在对话反思阶段获取用户对所有消息的反馈情况。
     * 系统可以根据这些投票数据来评估本轮对话中 AI 的表现，并调整相关知识条目的质量评分。</p>
     *
     * <p><strong>SQL 逻辑：</strong>
     * {@code SELECT * FROM knowledge_vote WHERE session_id = ? ORDER BY message_index ASC}</p>
     * <ul>
     *   <li>{@code WHERE session_id = ?} —— 精确匹配指定会话的全部投票记录</li>
     *   <li>{@code ORDER BY message_index ASC} —— 按消息序号升序排列，
     *       方便按对话的时间顺序依次处理投票反馈</li>
     * </ul>
     *
     * <p><strong>使用示例：</strong>在对话反思流程中，先获取该会话的全部投票记录，
     * 然后根据点赞/点踩情况决定哪些经验值得保留和提升评分，哪些应该降低评分或停用。</p>
     *
     * @param sessionId 会话 ID（{@code session_id}），标识某次对话会话
     * @return 该会话下的全部投票记录列表，按消息序号从低到高排序。如果没有投票记录则返回空列表。
     */
    @Select("SELECT * FROM knowledge_vote WHERE session_id = #{sessionId} ORDER BY message_index ASC")
    List<KnowledgeVote> findBySessionId(@Param("sessionId") String sessionId);
}
