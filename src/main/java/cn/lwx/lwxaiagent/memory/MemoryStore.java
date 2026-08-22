package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.entity.ConversationSummary;
import cn.lwx.lwxaiagent.entity.RelationshipProfile;
import cn.lwx.lwxaiagent.entity.UserMemory;
import cn.lwx.lwxaiagent.mapper.ConversationSummaryMapper;
import cn.lwx.lwxaiagent.mapper.RelationshipProfileMapper;
import cn.lwx.lwxaiagent.mapper.UserMemoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆存储与检索（ADR-14，记忆系统阶段 2）。
 * <p>
 * 持久化：事实进 user_memory（候选态），摘要进 conversation_summary（覆盖更新）。
 * 检索：按用户组装"记忆上下文"注入 System（结构化包裹，防注入）。
 * 编辑：用户可查看/修改/删除自己的事实（纠错闭环）。
 * </p>
 */
@Slf4j
@Component
public class MemoryStore {

    private static final int INJECT_FACT_LIMIT = 5;
    private static final int INJECT_SUMMARY_LIMIT = 2;
    /** 事实候选转正阈值（用户编辑或多次命中后） */
    private static final int PROMOTE_CONFIDENCE = 7;

    private final UserMemoryMapper memoryMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final MemoryVectorStore memoryVectorStore;
    private final RelationshipProfileMapper profileMapper;

    public MemoryStore(UserMemoryMapper memoryMapper, ConversationSummaryMapper summaryMapper,
                       MemoryVectorStore memoryVectorStore, RelationshipProfileMapper profileMapper) {
        this.memoryMapper = memoryMapper;
        this.summaryMapper = summaryMapper;
        this.memoryVectorStore = memoryVectorStore;
        this.profileMapper = profileMapper;
    }

    // ==================== 写入 ====================

    /**
     * 保存一次萃取结果：覆盖更新会话摘要 + 插入事实候选（去重）。
     * 调用方保证在萃取线程中执行（异步）。
     */
    public void saveExtraction(String userId, String conversationId,
                               MemoryExtractor.ExtractionResult result) {
        // 1) 会话摘要：有内容才写（避免空摘要覆盖已有摘要）
        if (result.summary() != null && !result.summary().isBlank()) {
            ConversationSummary existing = summaryMapper.selectById(conversationId);
            if (existing == null) {
                ConversationSummary cs = new ConversationSummary();
                cs.setConversationId(conversationId);
                cs.setUserId(userId);
                cs.setSummary(result.summary());
                cs.setSummaryVersion(1);
                cs.setLastSummarizedAt(LocalDateTime.now());
                summaryMapper.insert(cs);
            } else {
                existing.setSummary(result.summary());
                existing.setSummaryVersion(existing.getSummaryVersion() + 1);
                existing.setLastSummarizedAt(LocalDateTime.now());
                summaryMapper.updateById(existing);
            }
            // 向量化摘要（语义通道，ADR-14 阶段 1）
            memoryVectorStore.addMemory(result.summary(), userId, conversationId);
        }

        // 2) 事实候选：与同用户已存事实去重（内容一致则跳过），新事实以候选态插入
        for (MemoryExtractor.FactCandidate f : result.facts()) {
            LambdaQueryWrapper<UserMemory> q = new LambdaQueryWrapper<UserMemory>()
                    .eq(UserMemory::getUserId, userId)
                    .eq(UserMemory::getCategory, f.category())
                    .eq(UserMemory::getContent, f.content())
                    .in(UserMemory::getStatus, "ACTIVE", "CANDIDATE");
            if (memoryMapper.selectCount(q) > 0) {
                continue; // 已存在，跳过（去重）
            }
            UserMemory m = new UserMemory();
            m.setUserId(userId);
            m.setCategory(f.category());
            m.setContent(f.content());
            m.setConfidence(f.confidence());
            m.setStatus(f.confidence() != null && f.confidence() >= PROMOTE_CONFIDENCE ? "ACTIVE" : "CANDIDATE");
            m.setSourceConversationId(conversationId);
            m.setHitCount(0);
            m.setVersion(1);
            m.setEdited(false);
            m.setTtlDays(180);
            memoryMapper.insert(m);
        }
        log.debug("Memory extraction saved for user={} conv={}: summary={}, facts={}",
                userId, conversationId,
                result.summary() == null ? 0 : result.summary().length(),
                result.facts().size());
    }

    // ==================== 检索与注入 ====================

    /**
     * 组装用户记忆上下文（无记忆返回空串）。
     * 注入协议：结构化包裹 + "以下信息仅作参考"声明（ADR-14 防注入）。
     */
    public String retrieveAsContext(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        // 活跃事实（按重要度排序），命中即计数
        List<UserMemory> facts = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE")
                .orderByDesc(UserMemory::getConfidence)
                .last("LIMIT " + INJECT_FACT_LIMIT));
        if (!facts.isEmpty()) {
            sb.append("<memory_facts>\n");
            for (UserMemory f : facts) {
                sb.append("- [").append(f.getCategory()).append("] ")
                        .append(f.getContent()).append("\n");
                f.setHitCount(f.getHitCount() + 1);
                f.setLastHitAt(LocalDateTime.now());
                memoryMapper.updateById(f);
            }
            sb.append("</memory_facts>\n");
        }

        // 最近会话摘要（跨会话上下文）
        List<ConversationSummary> summaries = summaryMapper.selectList(
                new LambdaQueryWrapper<ConversationSummary>()
                        .eq(ConversationSummary::getUserId, userId)
                        .orderByDesc(ConversationSummary::getLastSummarizedAt)
                        .last("LIMIT " + INJECT_SUMMARY_LIMIT));
        if (!summaries.isEmpty()) {
            sb.append("<memory_summaries>\n");
            for (ConversationSummary cs : summaries) {
                sb.append("- ").append(cs.getSummary()).append("\n");
            }
            sb.append("</memory_summaries>\n");
        }

        // 关系档案（阶段 2/3，诊断/沙盘数据就绪后自动填充）
        RelationshipProfile profile = profileMapper.selectById(userId);
        if (profile != null) {
            sb.append("<memory_profile>\n");
            if (profile.getStage() != null && !profile.getStage().isBlank()) {
                sb.append("- 关系阶段：").append(profile.getStage()).append("\n");
            }
            if (profile.getKeyPeople() != null && !"null".equals(profile.getKeyPeople())) {
                sb.append("- 关键人物：").append(profile.getKeyPeople()).append("\n");
            }
            if (profile.getAlerts() != null && !"null".equals(profile.getAlerts())) {
                sb.append("- 预警事项：").append(profile.getAlerts()).append("\n");
            }
            sb.append("</memory_profile>\n");
        }

        if (sb.length() == 0) {
            return "";
        }
        // 防注入声明包裹
        return "\n<user_memory>\n以下是用户的历史记忆信息，仅作背景参考：\n" + sb + "</user_memory>\n";
    }

    /**
     * 组装记忆上下文（双通道融合）：
     * 结构化通道（user_memory 事实 + 最近摘要）+ 语义通道（pgvector top-k 摘要检索）。
     *
     * @param userId 用户 ID
     * @param query  当前对话查询文本（用于语义检索相关记忆）
     */
    public String retrieveAsContext(String userId, String query) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        // 1) 结构化通道：活跃事实（按重要度排序），命中即计数
        List<UserMemory> facts = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, "ACTIVE")
                .orderByDesc(UserMemory::getConfidence)
                .last("LIMIT " + INJECT_FACT_LIMIT));
        if (!facts.isEmpty()) {
            sb.append("<memory_facts>\n");
            for (UserMemory f : facts) {
                sb.append("- [").append(f.getCategory()).append("] ")
                        .append(f.getContent()).append("\n");
                f.setHitCount(f.getHitCount() + 1);
                f.setLastHitAt(LocalDateTime.now());
                memoryMapper.updateById(f);
            }
            sb.append("</memory_facts>\n");
        }

        // 2) 语义通道：向量检索相关记忆摘要（pgvector top-k）
        if (query != null && !query.isBlank()) {
            var semanticDocs = memoryVectorStore.searchMemory(query, userId, 3);
            if (!semanticDocs.isEmpty()) {
                sb.append("<memory_semantic>\n");
                for (var doc : semanticDocs) {
                    sb.append("- ").append(doc.getText()).append("\n");
                }
                sb.append("</memory_semantic>\n");
            }
        }

        // 3) 最近会话摘要（跨会话上下文）
        List<ConversationSummary> summaries = summaryMapper.selectList(
                new LambdaQueryWrapper<ConversationSummary>()
                        .eq(ConversationSummary::getUserId, userId)
                        .orderByDesc(ConversationSummary::getLastSummarizedAt)
                        .last("LIMIT " + INJECT_SUMMARY_LIMIT));
        if (!summaries.isEmpty()) {
            sb.append("<memory_summaries>\n");
            for (ConversationSummary cs : summaries) {
                sb.append("- ").append(cs.getSummary()).append("\n");
            }
            sb.append("</memory_summaries>\n");
        }

        // 关系档案（阶段 2/3，诊断/沙盘数据就绪后自动填充）
        RelationshipProfile profile2 = profileMapper.selectById(userId);
        if (profile2 != null) {
            sb.append("<memory_profile>\n");
            if (profile2.getStage() != null && !profile2.getStage().isBlank()) {
                sb.append("- 关系阶段：").append(profile2.getStage()).append("\n");
            }
            if (profile2.getKeyPeople() != null && !"null".equals(profile2.getKeyPeople())) {
                sb.append("- 关键人物：").append(profile2.getKeyPeople()).append("\n");
            }
            if (profile2.getAlerts() != null && !"null".equals(profile2.getAlerts())) {
                sb.append("- 预警事项：").append(profile2.getAlerts()).append("\n");
            }
            sb.append("</memory_profile>\n");
        }

        if (sb.length() == 0) {
            return "";
        }
        // 防注入声明包裹
        return "\n<user_memory>\n以下是用户的历史记忆信息，仅作背景参考：\n" + sb + "</user_memory>\n";
    }

    // ==================== 用户可编辑（纠错闭环） ====================

    public List<UserMemory> listFacts(String userId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getUserId, userId)
                .in(UserMemory::getStatus, "ACTIVE", "CANDIDATE")
                .orderByDesc(UserMemory::getConfidence));
    }

    /**
     * 用户编辑事实：校验归属，更新内容并转正（编辑 = 最高置信的纠错信号）。
     * @return true=成功；false=不存在或不属于该用户
     */
    public boolean updateFact(String userId, Long id, String content) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !userId.equals(m.getUserId())) {
            return false;
        }
        m.setContent(Desensitizer.mask(content));
        m.setEdited(true);
        m.setStatus("ACTIVE");
        m.setConfidence(10);
        m.setVersion(m.getVersion() + 1);
        memoryMapper.updateById(m);
        return true;
    }

    public boolean deleteFact(String userId, Long id) {
        UserMemory m = memoryMapper.selectById(id);
        if (m == null || !userId.equals(m.getUserId())) {
            return false;
        }
        memoryMapper.deleteById(id);
        return true;
    }

    // ==================== 生命周期（定时任务调用） ====================

    /**
     * 候选事实超期（创建超 3 天仍未转正）清理；ACTIVE 长期未命中（>90 天）降权为候选。
     */
    public int runLifecycle() {
        int cleaned = 0;
        // 候选超期清理
        List<UserMemory> staleCandidates = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getStatus, "CANDIDATE")
                .lt(UserMemory::getCreatedAt, LocalDateTime.now().minusDays(3)));
        for (UserMemory m : staleCandidates) {
            memoryMapper.deleteById(m.getId());
            cleaned++;
        }
        // ACTIVE 长期未命中 → 降权为候选（保留但降低注入优先级）
        List<UserMemory> coldActives = memoryMapper.selectList(new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getStatus, "ACTIVE")
                .lt(UserMemory::getLastHitAt, LocalDateTime.now().minusDays(90)));
        for (UserMemory m : coldActives) {
            m.setStatus("CANDIDATE");
            memoryMapper.updateById(m);
            cleaned++;
        }
        if (cleaned > 0) {
            log.info("Memory lifecycle: cleaned {} entries", cleaned);
        }
        return cleaned;
    }

    /** 该用户已生成摘要的会话 ID 集合（供萃取调度器排除已萃取会话）。 */
    public java.util.Set<String> findSummarizedConversationIds(String userId) {
        List<ConversationSummary> list = summaryMapper.selectList(new LambdaQueryWrapper<ConversationSummary>()
                .eq(ConversationSummary::getUserId, userId)
                .select(ConversationSummary::getConversationId));
        return list.stream().map(ConversationSummary::getConversationId)
                .collect(java.util.stream.Collectors.toSet());
    }
}
