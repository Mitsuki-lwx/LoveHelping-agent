package cn.lwx.lwxaiagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Agent 任务补偿调度器（ADR-3）：定时扫描卡死的 RUNNING/PENDING 任务 → FAILED。
 * 进程崩溃后重启，靠本扫描把"半途而废"的任务诚实标记为失败（用户可重提）。
 */
@Slf4j
@Component
public class AgentTaskScheduler {

    private final AgentTaskService taskService;

    public AgentTaskScheduler(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    /** 每 2 分钟扫描一次（心跳超时阈值 10 分钟，见 AgentTaskService） */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void compensateStale() {
        try {
            int marked = taskService.compensateStaleTasks();
            if (marked > 0) {
                log.info("Agent task compensation: {} stale task(s) marked FAILED", marked);
            }
        } catch (Exception e) {
            log.warn("Agent task compensation failed: {}", e.getMessage());
        }
    }
}
