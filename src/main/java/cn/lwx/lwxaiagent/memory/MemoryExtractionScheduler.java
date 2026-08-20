package cn.lwx.lwxaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆萃取调度器（ADR-14，记忆系统阶段 2）。
 * <p>
 * 周期扫描"有消息但尚未萃取摘要"的会话，异步执行记忆萃取（消息来源：message 表）。
 * 萃取失败仅告警（下次周期重试），不阻断对话。
 * </p>
 */
@Slf4j
@Component
public class MemoryExtractionScheduler {

    private static final int BATCH_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;
    private final MemoryService memoryService;
    private final MemoryExtractor extractor;
    private final MemoryStore memoryStore;

    public MemoryExtractionScheduler(JdbcTemplate jdbcTemplate,
                                     MemoryService memoryService,
                                     MemoryExtractor extractor,
                                     MemoryStore memoryStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
        this.extractor = extractor;
        this.memoryStore = memoryStore;
    }

    /**
     * 每 30 分钟扫描一次；启动 30 秒后首跑（便于 E2E/开发快速验证萃取链路）。
     */
    @Scheduled(fixedDelay = 1_800_000, initialDelay = 30_000)
    public void scanAndExtract() {
        try {
            List<String> candidates = findCandidates(BATCH_LIMIT);
            log.info("Memory extraction scan: {} candidate conversation(s)", candidates.size());
            if (candidates.isEmpty()) {
                return;
            }
            log.info("Memory extraction: {} conversation(s) to extract", candidates.size());
            for (String conversationId : candidates) {
                try {
                    extractOne(conversationId);
                } catch (Exception e) {
                    log.warn("Memory extraction failed for {}: {}", conversationId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Memory extraction scan failed: {}", e.getMessage());
        }
    }

    /**
     * 每日一次：记忆生命周期清理（候选超期清理、长期未命中降权，ADR-14）。
     */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    public void runLifecycle() {
        memoryStore.runLifecycle();
    }

    private void extractOne(String conversationId) {
        String userId = findUserId(conversationId);
        if (userId == null) {
            log.info("Memory extraction skip (no owner): {}", conversationId);
            return; // 会话未注册归属（匿名），不萃取
        }
        List<Message> history = memoryService.getHistory(conversationId);
        MemoryExtractor.ExtractionResult result = extractor.extract(conversationId, history);
        if (result.summary() == null || result.summary().isBlank()) {
            log.info("Memory extraction skip (blank summary): {} user={} history={}", conversationId, userId, history.size());
            return; // 无有效摘要，留待下次重试
        }
        memoryStore.saveExtraction(userId, conversationId, result);
        log.info("Memory extraction saved: {} user={} facts={}", conversationId, userId, result.facts().size());
    }

    /**
     * 查找"有归属、有消息、但尚无摘要"的会话（记忆只服务登录用户），
     * 取最近活跃的 N 个。JOIN user_conversations 确保只处理注册了归属的会话。
     * 消息来源为 message 表（Phase 2 真源，只看未删除消息）。
     */
    private List<String> findCandidates(int limit) {
        String sql = """
                SELECT m.conversation_id
                FROM message m
                INNER JOIN user_conversations uc ON uc.conversation_id = m.conversation_id
                WHERE m.deleted = 0 AND m.conversation_id NOT IN (
                    SELECT DISTINCT conversation_id FROM conversation_summary
                )
                GROUP BY m.conversation_id
                ORDER BY MAX(m.created_at) DESC
                LIMIT ?
                """;
        return jdbcTemplate.queryForList(sql, String.class, limit);
    }

    private String findUserId(String conversationId) {
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM user_conversations WHERE conversation_id = ? LIMIT 1",
                String.class, conversationId);
        return owners.isEmpty() ? null : owners.get(0);
    }
}
