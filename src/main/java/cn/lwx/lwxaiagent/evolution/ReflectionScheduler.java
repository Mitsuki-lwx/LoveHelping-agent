package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerBudget;
import cn.lwx.lwxaiagent.infrastructure.scheduler.SchedulerProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>反思调度器 —— 企业级数据库轮询模式</h1>
 *
 * <p><strong>核心作用：</strong>定期扫描对话会话，识别那些已经"冷却"（用户长时间未发言或回话总时长超限）
 * 的会话，并自动触发技能反思（Skill Reflection）流程，从对话中提取可复用的经验知识。</p>
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>每隔 <b>5 分钟</b>（固定延迟 {@code 300_000} 毫秒）扫描一次 {@code SPRING_AI_CHAT_MEMORY} 表</li>
 *   <li>通过 SQL 查询找出满足以下任一条件的会话：
 *     <ul>
 *       <li><b>空闲超时：</b>最后一条消息距今超过 {@code extractDelaySeconds} 秒（用户已停止对话）</li>
 *       <li><b>总时长超限：</b>会话总持续时间超过 {@code idleTimeoutSeconds} 秒（安全兜底，防止长时间活跃会话永远不会触发反思）</li>
 *     </ul>
 *   </li>
 *   <li>自动跳过已在 {@code evolution_skill.source_session_id} 中记录过的会话（去重）</li>
 *   <li>对每个符合条件的会话，调用 {@link SkillReflector#reflect} 进行反思提取</li>
 * </ol>
 *
 * <h2>设计理念</h2>
 * <p>不依赖内存中的定时器，完全基于数据库状态轮询，具有以下优势：</p>
 * <ul>
 *   <li><b>重启安全：</b>应用重启不会丢失待处理的会话状态</li>
 *   <li><b>分布式友好：</b>多实例部署时，通过数据库级别的去重（{@code evolution_skill} 表记录）避免重复处理</li>
 *   <li><b>可观测：</b>所有状态都在数据库中，方便监控和排查问题</li>
 * </ul>
 *
 * @see SkillReflector 技能反思器 —— 执行具体的反思逻辑
 * @see EvolutionProperties 进化系统配置属性 —— 提供超时阈值等参数
 */
@Slf4j
@Component
public class ReflectionScheduler {

    /**
     * Spring JDBC 模板，用于执行数据库查询操作
     */
    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 技能反思器，负责对指定会话执行 LLM 反思并提取可复用技能
     */
    @Resource
    private SkillReflector skillReflector;

    /**
     * 进化系统配置属性，提供空闲超时、提取延迟等参数
     */
    private final EvolutionProperties props;

    /** 后台调度预算（ADR-20）：配额 / 退避 / 时间预算统一问询 */
    private final SchedulerBudget budget;

    /** 后台调度配置（节奏 / 批量上限，ADR-20） */
    private final SchedulerProperties schedulerProperties;

    /**
     * 构造函数，通过构造器注入进化系统配置属性。
     *
     * @param props 进化系统配置属性对象，包含各个超时和阈值参数
     */
    public ReflectionScheduler(EvolutionProperties props, SchedulerBudget budget, SchedulerProperties schedulerProperties) {
        this.props = props;
        this.budget = budget;
        this.schedulerProperties = schedulerProperties;
    }

    /**
     * <h3>扫描并触发反思</h3>
     *
     * <p>由 Spring 定时任务框架驱动，每 <b>5 分钟</b>执行一次，<b>首次延迟 60 秒</b>启动。</p>
     *
     * <p>执行逻辑：</p>
     * <ol>
     *   <li>检查进化功能是否启用（{@code evolution.enabled}），若禁用则跳过</li>
     *   <li>调用 {@link #findReadySessions()} 查找满足反思条件的会话列表</li>
     *   <li>若无符合条件的会话，输出 DEBUG 日志后返回</li>
     *   <li>对每个符合条件的会话，调用 {@link SkillReflector#reflect(String, String)} 执行反思</li>
     *   <li>单个会话的反思失败不影响其他会话的处理（{@code try-catch} 包裹）</li>
     * </ol>
     *
     * <p><b>定时参数：</b></p>
     * <ul>
     *   <li>{@code fixedDelay = 300_000} 毫秒（5 分钟）：每次执行完成后等待 5 分钟再执行下一次</li>
     *   <li>{@code initialDelay = 60_000} 毫秒（60 秒）：应用启动后延迟 60 秒再开始第一次执行，
     *       确保 Spring 容器及其他组件完全初始化</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${app.scheduler.reflect.fixed-delay-ms:300000}", initialDelay = 60_000)
    public void scanAndReflect() {
        if (!props.isEnabled()) {
            log.debug("Evolution disabled, skipping reflection scan");
            return;
        }
        if (!budget.permitted("reflect") || !budget.backoffAllowsRun("reflect")) {
            return;
        }

        List<String> sessions = findReadySessions();
        if (sessions.isEmpty()) {
            budget.recordOutcome("reflect", 0, 0, 0);
            return;
        }

        int allow = budget.allowance("reflect", Math.min(sessions.size(), schedulerProperties.getReflect().getBatchLimit()));
        if (allow <= 0) {
            budget.recordOutcome("reflect", sessions.size(), 0, 0);
            return;
        }

        log.info("Reflection scan: {} session(s) ready for reflection (budget allow {})", sessions.size(), allow);
        long start = System.currentTimeMillis();
        int processed = 0;
        for (String sessionId : sessions) {
            if (processed >= allow || System.currentTimeMillis() - start > budget.maxRunMs()) {
                log.info("Reflection round budget reached (allow={}): {}/{} processed, rest deferred to next round", allow, processed, sessions.size());
                break;
            }
            try {
                budget.consume("reflect", 1);
                skillReflector.reflect(sessionId, "default");
                processed++;
            } catch (Exception e) {
                log.error("Reflection failed for session {}: {}", sessionId, e.getMessage());
            }
        }
        budget.recordOutcome("reflect", sessions.size(), processed, System.currentTimeMillis() - start);
    }

    /**
     * <h3>查找满足反思条件的会话 ID 列表</h3>
     *
     * <p>通过 SQL 查询从 {@code message} 表中筛选出需要进行反思的会话。</p>
     *
     * <h4>查询条件（同时满足以下两点）：</h4>
     * <ol>
     *   <li><b>尚未反思：</b>会话 ID 不在 {@code evolution_skill.source_session_id} 中（去重，避免重复提取）</li>
     *   <li><b>满足触发条件（二选一）：</b>
     *     <ul>
     *       <li><b>空闲超时：</b>最后一条消息距当前时间的秒数 &gt; {@code extractDelaySeconds} 秒
     *           —— 即用户在指定时间内没有新消息</li>
     *       <li><b>总时长超限：</b>会话总持续时间（最后一条消息时间 - 第一条消息时间）&gt; {@code idleTimeoutSeconds} 秒
     *           —— 安全兜底机制，防止活跃会话因一直有新消息而永远不会触发反思</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <h4>SQL 逻辑说明：</h4>
     * <ul>
     *   <li>使用 {@code GROUP BY conversation_id} 对每个会话聚合计算</li>
     *   <li>{@code TIMESTAMPDIFF(SECOND, MAX(created_at), NOW())} 计算最后一条消息的空闲时长</li>
     *   <li>{@code TIMESTAMPDIFF(SECOND, MIN(created_at), MAX(created_at))} 计算会话总时长</li>
     *   <li>{@code LIMIT 20} 限制每次最多处理 20 个会话，防止一次性处理过多导致系统压力</li>
     * </ul>
     *
     * @return 满足反思条件的会话 ID 列表（{@code conversation_id}），最多 20 条
     */
    private List<String> findReadySessions() {
        // 会话总时长使用 MAX(created_at) - MIN(created_at) 计算
        // 空闲时间使用 NOW() - MAX(created_at) 计算
        // LIMIT 使用配置的批量上限（ADR-20 app.scheduler.reflect.batch-limit）
        String sql = """
                SELECT m.conversation_id
                FROM message m
                WHERE m.deleted = 0 AND m.conversation_id NOT IN (
                    SELECT DISTINCT source_session_id
                    FROM evolution_skill
                    WHERE source_session_id IS NOT NULL
                )
                GROUP BY m.conversation_id
                HAVING
                    TIMESTAMPDIFF(SECOND, MAX(m.created_at), NOW()) > ?
                    OR TIMESTAMPDIFF(SECOND, MIN(m.created_at), MAX(m.created_at)) > ?
                LIMIT ?
                """;

        return jdbcTemplate.queryForList(
                sql,
                String.class,
                props.getExtractDelaySeconds(),
                props.getIdleTimeoutSeconds(),
                schedulerProperties.getReflect().getBatchLimit());
    }
}
