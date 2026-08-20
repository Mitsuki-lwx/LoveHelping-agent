package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>会话追踪器 —— 记录会话活动状态</h1>
 *
 * <p><strong>核心作用：</strong>追踪并记录每个对话会话的活动状态，提供会话级别的监控和日志记录能力。</p>
 *
 * <h2>架构演进说明</h2>
 * <p>在早期版本中，该类负责管理会话的内存状态（通过 {@code ConcurrentHashMap} 记录最后活跃时间）并直接触发反思。
 * 但这种方式存在以下问题：</p>
 * <ul>
 *   <li><b>重启丢失：</b>所有内存中的会话状态在应用重启后全部丢失</li>
 *   <li><b>分布式不友好：</b>多实例部署时每个实例的会话状态相互独立，无法协调</li>
 * </ul>
 *
 * <p>因此反思触发的职责已迁移到 {@link ReflectionScheduler}（数据库轮询模式），通过直接查询
 * {@code SPRING_AI_CHAT_MEMORY} 表来判断会话状态。本类目前仅保留以下轻量职责：</p>
 * <ul>
 *   <li>记录会话的活动日志（调试和监控用途）</li>
 *   <li>作为未来扩展点的占位（如会话级别的统计分析、活跃度报告等）</li>
 * </ul>
 *
 * @see ReflectionScheduler 反思调度器 —— 当前负责触发反思的核心组件
 * @see EvolutionProperties 进化系统配置属性
 */
@Slf4j
@Component
public class SessionTracker {

    /**
     * 进化系统配置属性，提供超时阈值等参数
     */
    private final EvolutionProperties props;

    /**
     * 构造函数，通过构造器注入配置属性。
     *
     * @param props 进化系统配置属性对象
     */
    public SessionTracker(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * <h3>标记会话有新消息</h3>
     *
     * <p>当用户在某个对话中发送新消息时被调用，用于记录会话活动事件。</p>
     *
     * <p><strong>注意：</strong>反思触发已不再由此方法负责，而是由
     * {@link ReflectionScheduler#scanAndReflect} 通过独立的数据库轮询机制处理。
     * 此方法仅记录 DEBUG 级别的日志，供开发调试和运维监控使用。</p>
     *
     * @param chatId   会话 ID（对应 {@code conversation_id}，唯一标识一个对话会话）
     * @param tenantId 租户 ID（用于多租户场景下的隔离，如不同的机构或用户组）
     */
    public void onMessageSent(String chatId, String tenantId) {
        log.debug("Session activity: chatId={}, tenant={}", chatId, tenantId);
    }
}
