package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话追踪器 —— 记录会话活跃状态。
 * <p>
 * 反思触发已迁移至 {@link ReflectionScheduler}（DB 轮询模式），
 * 此类保留用于活跃会话的统计和日志。
 */
@Slf4j
@Component
public class SessionTracker {

    private final EvolutionProperties props;

    public SessionTracker(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * 标记会话有新消息。
     * <p>
     * 反思触发由 {@link ReflectionScheduler#scanAndReflect} 通过
     * DB 轮询独立处理，此处仅记录日志。
     */
    public void onMessageSent(String chatId, String tenantId) {
        log.debug("Session activity: chatId={}, tenant={}", chatId, tenantId);
    }
}
