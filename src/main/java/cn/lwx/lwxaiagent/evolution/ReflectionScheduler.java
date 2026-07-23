package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 反思调度器 —— 企业级 DB 轮询模式。
 * <p>
 * 不依赖内存定时器，每 5 分钟扫描 {@code SPRING_AI_CHAT_MEMORY} 表，
 * 找出满足以下任一条件的会话并触发技能反思：
 * <ul>
 *   <li>最后一条消息距今超过 {@code extractDelaySeconds} 秒（会话空闲超时）</li>
 *   <li>会话总时长超过 {@code idleTimeoutSeconds} 秒（兜底，防止长会话永远不触发）</li>
 * </ul>
 * 已反思过的会话（{@code evolution_skill.source_session_id} 有记录）自动跳过。
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
     * 每 5 分钟扫描一次，找出可反思的会话。
     * 首次延迟 60 秒等待应用完全就绪。
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
     * 查询满足反思条件的会话 ID。
     * <p>
     * 条件：
     * <ol>
     *   <li>未在 {@code evolution_skill} 中记录过（去重）</li>
     *   <li>最后一条消息距今 &gt; {@code extractDelaySeconds} 秒
     *       —— 或 —— 会话总时长 &gt; {@code idleTimeoutSeconds} 秒</li>
     * </ol>
     */
    private List<String> findReadySessions() {
        // 会话总时长用 MAX(timestamp) - MIN(timestamp)
        // 空闲时间用 NOW() - MAX(timestamp)
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
