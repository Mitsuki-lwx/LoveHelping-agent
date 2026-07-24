package cn.lwx.lwxaiagent.evolution;

import cn.lwx.lwxaiagent.evolution.config.EvolutionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Session Tracker -- records session activity state.
 * <p>
 * Reflection triggering has been migrated to {@link ReflectionScheduler} (DB polling mode),
 * this class is retained for active session statistics and logging.
 */
@Slf4j
@Component
public class SessionTracker {

    private final EvolutionProperties props;

    public SessionTracker(EvolutionProperties props) {
        this.props = props;
    }

    /**
     * Mark a session as having new messages.
     * <p>
     * Reflection triggering is handled independently by {@link ReflectionScheduler#scanAndReflect}
     * via DB polling; this method only logs the event.
     */
    public void onMessageSent(String chatId, String tenantId) {
        log.debug("Session activity: chatId={}, tenant={}", chatId, tenantId);
    }
}
