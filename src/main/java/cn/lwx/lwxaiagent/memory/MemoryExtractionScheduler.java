package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerBudget;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆萃取调度器（ADR-14，记忆系统阶段 2；预算约束见 ADR-20）。
 * <p>
 * 周期扫描"有消息但尚未萃取摘要"的会话，异步执行记忆萃取（消息来源：message 表）。
 * 萃取失败仅告警（下次周期重试），不阻断对话。
 * </p>
 */
@Slf4j
@Component
public class MemoryExtractionScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final MemoryService memoryService;
    private final MemoryExtractor extractor;
    private final MemoryStore memoryStore;
    private final SchedulerBudget budget;
    private final SchedulerProperties schedulerProperties;

    public MemoryExtractionScheduler(JdbcTemplate jdbcTemplate,
                                     MemoryService memoryService,
                                     MemoryExtractor extractor,
                                     MemoryStore memoryStore,
                                     SchedulerBudget budget,
                                     SchedulerProperties schedulerProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
        this.extractor = extractor;
        this.memoryStore = memoryStore;
        this.budget = budget;
        this.schedulerProperties = schedulerProperties;
    }

    /**
     * 每 30 分钟扫描一次（间隔可配 app.scheduler.extract.fixed-delay-ms）；启动 30 秒后首跑。
     * 受 ADR-20 预算约束：开关 / 配额 / 空转退避 / 单轮时间预算。
     */
    @Scheduled(fixedDelayString = "${app.scheduler.extract.fixed-delay-ms:1800000}", initialDelay = 30_000)
    public void scanAndExtract() {
        if (!budget.permitted("extract") || !budget.backoffAllowsRun("extract")) {
            return;
        }
        long start = System.currentTimeMillis();
        try {
            int batchLimit = schedulerProperties.getExtract().getBatchLimit();
            List<String> candidates = findCandidates(batchLimit);
            if (candidates.isEmpty()) {
                budget.recordOutcome("extract", 0, 0, System.currentTimeMillis() - start);
                return;
            }

            int allow = budget.allowance("extract", candidates.size());
            if (allow <= 0) {
                budget.recordOutcome("extract", candidates.size(), 0, System.currentTimeMillis() - start);
                return;
            }

            log.info("Memory extraction scan: {} candidate conversation(s) (budget allow {})", candidates.size(), allow);
            int processed = 0;
            for (String conversationId : candidates) {
                if (processed >= allow || System.currentTimeMillis() - start > budget.maxRunMs()) {
                    log.info("Extraction round budget reached (allow={}): {}/{} processed, rest deferred to next round", allow, processed, candidates.size());
                    break;
                }
                try {
                    // 萃取 LLM + 写记忆 embedding 各计 1 次配额（ADR-20：embedding 也吃配额）
                    budget.consume("extract", 2);
                    extractOne(conversationId);
                    processed++;
                } catch (Exception e) {
                    log.warn("Memory extraction failed for {}: {}", conversationId, e.getMessage());
                }
            }
            budget.recordOutcome("extract", candidates.size(), processed, System.currentTimeMillis() - start);
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
