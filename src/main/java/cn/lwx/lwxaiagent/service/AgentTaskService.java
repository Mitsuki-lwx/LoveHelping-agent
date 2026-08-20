package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.AgentTask;
import cn.lwx.lwxaiagent.mapper.AgentTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 任务生命周期服务（ADR-3）。
 * <p>
 * 提交落库（幂等）→ 执行中更新状态/心跳 → 完成/失败收尾；
 * 崩溃补偿：RUNNING 且心跳超时（默认 10 分钟）→ 扫描标记 FAILED（诚实告知，可重提）。
 * </p>
 */
@Slf4j
@Service
public class AgentTaskService {

    /** 心跳超时阈值（分钟）：Agent 任务一般 30s~2min，10 分钟为安全边界 */
    private static final int HEARTBEAT_TIMEOUT_MINUTES = 10;

    private final AgentTaskMapper taskMapper;

    public AgentTaskService(AgentTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 提交任务：幂等（同 idempotencyKey 已存在则返回已有任务），否则落库 PENDING。
     * @return 任务（新创建或已存在）
     */
    public AgentTask submit(String userId, String instruction, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            AgentTask existing = taskMapper.selectOne(new LambdaQueryWrapper<AgentTask>()
                    .eq(AgentTask::getIdempotencyKey, idempotencyKey));
            if (existing != null) {
                log.info("Idempotent submit: reuse task {} for key {}", existing.getId(), idempotencyKey);
                return existing;
            }
        }
        AgentTask task = new AgentTask();
        task.setTenantId("default");
        task.setUserId(userId);
        task.setInstruction(instruction);
        task.setStatus(AgentTask.STATUS_PENDING);
        task.setHeartbeatAt(LocalDateTime.now());
        task.setTokenUsage(0L);
        task.setIdempotencyKey(idempotencyKey);
        taskMapper.insert(task);
        return task;
    }

    /** 开始执行：PENDING → RUNNING（记录心跳） */
    public void start(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(AgentTask.STATUS_RUNNING);
        task.setHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("AgentTask[{}] STARTED", taskId);
    }

    /** 完成：→ SUCCESS（带产物引用） */
    public void succeed(Long taskId, String resultRef, long tokenUsage) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(AgentTask.STATUS_SUCCESS);
        task.setResultRef(resultRef);
        task.setTokenUsage(tokenUsage);
        task.setHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("AgentTask[{}] SUCCEEDED", taskId);
    }

    /** 失败：→ FAILED（归因） */
    public void fail(Long taskId, String errorCode, String errorMsg) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (AgentTask.STATUS_SUCCESS.equals(task.getStatus()) || AgentTask.STATUS_CANCELLED.equals(task.getStatus())) {
            return; // 终态不回退
        }
        task.setStatus(AgentTask.STATUS_FAILED);
        task.setErrorCode(errorCode);
        task.setErrorMsg(errorMsg != null && errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg);
        task.setHeartbeatAt(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("AgentTask[{}] FAILED: {}", taskId, errorCode);
    }

    /** 取消：→ CANCELLED */
    public void cancel(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (AgentTask.STATUS_SUCCESS.equals(task.getStatus()) || AgentTask.STATUS_FAILED.equals(task.getStatus())) {
            return;
        }
        task.setStatus(AgentTask.STATUS_CANCELLED);
        taskMapper.updateById(task);
    }

    /**
     * 崩溃补偿：RUNNING 且心跳超时 → FAILED。
     * @return 本次标记失败的任务数
     */
    public int compensateStaleTasks() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(HEARTBEAT_TIMEOUT_MINUTES);
        List<AgentTask> stale = taskMapper.selectList(new LambdaQueryWrapper<AgentTask>()
                .eq(AgentTask::getStatus, AgentTask.STATUS_RUNNING)
                .lt(AgentTask::getHeartbeatAt, threshold)
                .or()
                .eq(AgentTask::getStatus, AgentTask.STATUS_PENDING)
                .lt(AgentTask::getCreatedAt, threshold));
        int count = 0;
        for (AgentTask task : stale) {
            task.setStatus(AgentTask.STATUS_FAILED);
            task.setErrorCode("TIMEOUT");
            task.setErrorMsg("任务执行超时（进程可能已重启），请重新提交");
            taskMapper.updateById(task);
            count++;
        }
        if (count > 0) {
            log.warn("Agent task compensation: marked {} stale task(s) as FAILED", count);
        }
        return count;
    }

    public AgentTask get(Long taskId) {
        return taskMapper.selectById(taskId);
    }
}
