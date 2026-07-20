package cn.lwx.lwxaiagent.evolution;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class SessionTracker {

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingExtractions = new ConcurrentHashMap<>();
    private final TaskScheduler taskScheduler;

    @Resource
    private ConversationExtractor conversationExtractor;

    public SessionTracker(TaskScheduler evolutionTaskScheduler) {
        this.taskScheduler = evolutionTaskScheduler;
    }

    public void onMessageSent(String chatId, String tenantId) {
        ScheduledFuture<?> existing = pendingExtractions.remove(chatId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
            log.debug("Cancelled pending extraction for session {}", chatId);
        }

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> {
                    log.info("Triggering extraction for session {}", chatId);
                    conversationExtractor.extractSession(chatId, tenantId);
                    pendingExtractions.remove(chatId);
                },
                Instant.now().plusSeconds(30));

        pendingExtractions.put(chatId, future);
        log.debug("Scheduled extraction for session {} in 30s", chatId);
    }
}
