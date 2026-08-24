package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.*;
import cn.lwx.lwxaiagent.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class DeleteService {

    private final UserMapper userMapper;
    private final UserMemoryMapper userMemoryMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final EvolutionSkillMapper evolutionSkillMapper;
    private final AgentTaskMapper agentTaskMapper;
    private final KnowledgeVoteMapper knowledgeVoteMapper;
    private final MessageMapper messageMapper;
    private final MessageMediaMapper messageMediaMapper;
    private final SandboxSessionMapper sandboxSessionMapper;
    private final SandboxMemoryMapper sandboxMemoryMapper;
    private final InsightRecordMapper insightRecordMapper;

    public DeleteService(UserMapper userMapper, UserMemoryMapper userMemoryMapper,
                         ConversationSummaryMapper conversationSummaryMapper,
                         EvolutionSkillMapper evolutionSkillMapper,
                         AgentTaskMapper agentTaskMapper,
                         KnowledgeVoteMapper knowledgeVoteMapper,
                         MessageMapper messageMapper, MessageMediaMapper messageMediaMapper,
                         SandboxSessionMapper sandboxSessionMapper,
                         SandboxMemoryMapper sandboxMemoryMapper,
                         InsightRecordMapper insightRecordMapper) {
        this.userMapper = userMapper;
        this.userMemoryMapper = userMemoryMapper;
        this.conversationSummaryMapper = conversationSummaryMapper;
        this.evolutionSkillMapper = evolutionSkillMapper;
        this.agentTaskMapper = agentTaskMapper;
        this.knowledgeVoteMapper = knowledgeVoteMapper;
        this.messageMapper = messageMapper;
        this.messageMediaMapper = messageMediaMapper;
        this.sandboxSessionMapper = sandboxSessionMapper;
        this.sandboxMemoryMapper = sandboxMemoryMapper;
        this.insightRecordMapper = insightRecordMapper;
    }

    @Transactional
    public void deleteUserData(String userId) {
        // 1. 获取该用户的所有会话 ID
        List<Message> userMsgs = messageMapper.selectList(
                new QueryWrapper<Message>().eq("user_id", userId).select("DISTINCT conversation_id"));
        List<String> convIds = userMsgs.stream().map(Message::getConversationId).distinct().filter(c -> c != null).toList();
        log.info("Deleting user {}: {} conversations", userId, convIds.size());

        // 2. 软删消息
        if (!convIds.isEmpty()) {
            for (String cid : convIds) {
                messageMapper.update(null, new UpdateWrapper<Message>().eq("conversation_id", cid).set("deleted", 1));
            }
            messageMediaMapper.update(null, new UpdateWrapper<MessageMedia>()
                    .eq("user_id", userId).set("status", "DELETED"));
        }

        // 3. 删除用户记忆
        userMemoryMapper.delete(new QueryWrapper<UserMemory>().eq("user_id", userId));

        // 4. 删除会话摘要
        conversationSummaryMapper.delete(new QueryWrapper<ConversationSummary>().eq("user_id", userId));

        // 5. 标记 Skill 为 inactive
        if (!convIds.isEmpty()) {
            for (String cid : convIds) {
                evolutionSkillMapper.update(null, new UpdateWrapper<EvolutionSkill>()
                        .eq("source_session_id", cid).set("is_active", false));
            }
        }

        // 6. 删除 Agent 任务
        agentTaskMapper.delete(new QueryWrapper<AgentTask>().eq("user_id", userId));

        // 7. 删除投票
        if (!convIds.isEmpty()) {
            knowledgeVoteMapper.delete(new QueryWrapper<KnowledgeVote>()
                    .eq("tenant_id", "default").in("session_id", convIds));
        }

        // 7.5 删除沙盘会话 + 沙盘记忆（Phase 4）
        sandboxSessionMapper.delete(new QueryWrapper<SandboxSession>()
                .eq("user_id", userId));
        sandboxMemoryMapper.delete(new QueryWrapper<SandboxMemory>()
                .eq("user_id", userId));

        // 7.6 删除对话洞察记录（Phase 4）
        insightRecordMapper.delete(new QueryWrapper<InsightRecord>()
                .eq("user_id", userId));

        // 8. 禁用用户账号
        userMapper.update(null, new UpdateWrapper<User>().eq("username", userId).set("enabled", false));

        log.info("User {} data deleted successfully", userId);
    }
}