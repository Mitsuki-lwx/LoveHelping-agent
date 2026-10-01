package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import cn.lwx.lwxaiagent.entity.*;
import cn.lwx.lwxaiagent.infrastructure.retention.RetentionPurge;
import cn.lwx.lwxaiagent.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /**
     * PG 侧向量库（ADR-5 级联）。⛔ 这里**自己建 DataSource** 而不是注入 bean：
     * 与 {@code PgVectorVectorStoreConfig} / {@code KnowledgeSqlSearch} 的既定做法一致
     * （本仓 PG 数据源从来不是 Spring bean，各自按 {@code PgvectorProperties} 构造）。
     */
    private final JdbcTemplate pgJdbc;

    public DeleteService(UserMapper userMapper, UserMemoryMapper userMemoryMapper,
                         ConversationSummaryMapper conversationSummaryMapper,
                         EvolutionSkillMapper evolutionSkillMapper,
                         AgentTaskMapper agentTaskMapper,
                         KnowledgeVoteMapper knowledgeVoteMapper,
                         MessageMapper messageMapper, MessageMediaMapper messageMediaMapper,
                         SandboxSessionMapper sandboxSessionMapper,
                         SandboxMemoryMapper sandboxMemoryMapper,
                         InsightRecordMapper insightRecordMapper,
                         PgvectorProperties pgvectorProperties) {
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
        this.pgJdbc = new JdbcTemplate(DataSourceBuilder.create()
                .url(pgvectorProperties.getUrl())
                .username(pgvectorProperties.getUsername())
                .password(pgvectorProperties.getPassword())
                .driverClassName(pgvectorProperties.getDriverClassName())
                .build());
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

        // 7.7 删除该用户**由私信派生的记忆向量**（ADR-5 的"级联到向量库"）
        //     ⛔ 2026-10-01 前这里**根本没有这一步** —— ADR-5 的核心承诺（《个保法》§47 删除权：
        //        向量是从私信衍生的个人信息）长期只停在文档里，全仓没有任何删向量的代码路径。
        //     ⛔ 失败**不阻断注销**（账号已禁用，主诉求已达成），但必须**ERROR 级喊出来** ——
        //        静默留下向量才是真正的合规事故。残留向量目前**没有重试任务**（已登记为待办）。
        try {
            int vectors = RetentionPurge.purgeUserMemoryVectors(pgJdbc, userId);
            log.info("User {}: deleted {} memory vector(s) from pgvector (ADR-5)", userId, vectors);
        } catch (Exception e) {
            log.error("[ADR-5] 用户 {} 的记忆向量删除失败 —— 向量可能残留，需人工/重试清理：{}",
                    userId, e.getMessage());
        }

        // 8. 禁用用户账号
        userMapper.update(null, new UpdateWrapper<User>().eq("username", userId).set("enabled", false));

        log.info("User {} data deleted successfully", userId);
    }
}