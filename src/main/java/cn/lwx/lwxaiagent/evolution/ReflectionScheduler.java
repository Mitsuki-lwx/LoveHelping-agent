package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reflection Scheduler -- enterprise-grade DB polling mode.
 * <p>
 * Does not rely on in-memory timers. Scans the {@code SPRING_AI_CHAT_MEMORY} table every 5 minutes,
 * identifies sessions meeting any of the following conditions and triggers skill reflection:
 * <ul>
 *   <li>Last message is older than {@code extractDelaySeconds} seconds (session idle timeout)</li>
 *   <li>Session total duration exceeds {@code idleTimeoutSeconds} seconds (safety net, prevents long sessions from never triggering)</li>
 * </ul>
 * Already reflected sessions (recorded in {@code evolution_skill.source_session_id}) are automatically skipped.
 */
@Slf4j
@Component
public class ReflectionScheduler {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private SkillReflector skillReflector;

    private final EvolutionProperties props;

    public ReflectionScheduler(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * Scan every 5 minutes to find sessions ready for reflection.
     * Initial delay of 60 seconds to wait for the application to fully start.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scanAndReflect() {
        if (!props.isEnabled()) {
            log.debug("Evolution disabled, skipping reflection scan");
            return;
        }

        List<String> sessions = findReadySessions();
        if (sessions.isEmpty()) {
            log.debug("Reflection scan: no sessions ready for reflection");
            return;
        }

        log.info("Reflection scan: {} session(s) ready for reflection", sessions.size());
        for (String sessionId : sessions) {
            try {
                skillReflector.reflect(sessionId, "default");
            } catch (Exception e) {
                log.error("Reflection failed for session {}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Query session IDs that satisfy reflection conditions.
     * <p>
     * Conditions:
     * <ol>
     *   <li>Not yet recorded in {@code evolution_skill} (deduplication)</li>
     *   <li>Last message age > {@code extractDelaySeconds} seconds
     *       -- OR -- total session duration > {@code idleTimeoutSeconds} seconds</li>
     * </ol>
     */
    private List<String> findReadySessions() {
        // Total session duration uses MAX(timestamp) - MIN(timestamp)
        // Idle time uses NOW() - MAX(timestamp)
        String sql = """
                SELECT m.conversation_id
                FROM SPRING_AI_CHAT_MEMORY m
                WHERE m.conversation_id NOT IN (
                    SELECT DISTINCT source_session_id
                    FROM evolution_skill
                    WHERE source_session_id IS NOT NULL
                )
                GROUP BY m.conversation_id
                HAVING
                    TIMESTAMPDIFF(SECOND, MAX(m.`timestamp`), NOW()) > ?
                    OR TIMESTAMPDIFF(SECOND, MIN(m.`timestamp`), MAX(m.`timestamp`)) > ?
                LIMIT 20
                """;

        return jdbcTemplate.queryForList(
                sql,
                String.class,
                props.getExtractDelaySeconds(),
                props.getIdleTimeoutSeconds());
    }
}
