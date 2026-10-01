package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.entity.AgentTask;
import cn.lwx.lwxaiagent.mapper.AgentTaskMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>Agent 任务状态机（此前覆盖率 1.2%）</h3>
 *
 * <p>{@code docs/09} §2 把「<b>Agent 状态机非法迁移拒绝</b>」列为单元测试重点，而本类几乎没测。
 * 它管的是**用户可见的进度**（提交 → 执行 → 完成/失败/取消）+ **崩溃补偿**，
 * 一旦状态回退或误标，用户看到的进度就与事实相反（例如"已完成"又变"已失败"）。</p>
 *
 * <p>钉的五条：幂等键复用 · 终态不回退（fail/cancel 各有守卫）· 错误信息截断 ·
 * 心跳写入 · 崩溃补偿只标记「超时未心跳」的 RUNNING/PENDING。</p>
 */
@DisplayName("AgentTaskService：状态机与崩溃补偿")
class AgentTaskServiceTest {

    private AgentTaskMapper mapper;
    private MeterRegistry meters;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentTaskMapper.class);
        meters = new SimpleMeterRegistry();
        service = new AgentTaskService(mapper, meters);
    }

    private AgentTask existing(String status) {
        AgentTask t = new AgentTask();
        t.setId(7L);
        t.setStatus(status);
        return t;
    }

    private void givenTask(String status) {
        when(mapper.selectById(7L)).thenReturn(existing(status));
    }

    private AgentTask capturedUpdate() {
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(mapper).updateById(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("submit：PENDING 起步、tenant 固定 default、心跳已写、tokenUsage=0")
    void submit_creates_pending_task() {
        AgentTask task = service.submit("u1", "帮我分析", "key-1");

        assertThat(task.getStatus()).isEqualTo(AgentTask.STATUS_PENDING);
        assertThat(task.getTenantId()).as("ADR-13：tenant 固定 default").isEqualTo("default");
        assertThat(task.getHeartbeatAt()).as("心跳必须一开始就写，否则补偿会把它当陈旧").isNotNull();
        assertThat(task.getTokenUsage()).isZero();
        verify(mapper).insert(any(AgentTask.class));
    }

    @Test
    @DisplayName("幂等键：同 key 再提交**复用同一任务**，不重复插入（防重复扣费/重复执行）")
    void idempotency_key_reuses_task() {
        when(mapper.selectOne(any())).thenReturn(existing(AgentTask.STATUS_RUNNING));

        AgentTask got = service.submit("u1", "同样的指令", "key-1");

        assertThat(got.getId()).isEqualTo(7L);
        verify(mapper, never()).insert(any(AgentTask.class));
    }

    @Test
    @DisplayName("start：PENDING → RUNNING，并刷新心跳")
    void start_marks_running_and_refreshes_heartbeat() {
        givenTask(AgentTask.STATUS_PENDING);

        service.start(7L);

        AgentTask updated = capturedUpdate();
        assertThat(updated.getStatus()).isEqualTo(AgentTask.STATUS_RUNNING);
        assertThat(updated.getHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("⛔ 终态不回退：SUCCESS 任务再 fail → **不动**（否则用户看到'已完成'变'已失败'）")
    void fail_does_not_regress_success() {
        givenTask(AgentTask.STATUS_SUCCESS);

        service.fail(7L, "E1", "boom");

        verify(mapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    @DisplayName("⛔ 终态不回退：SUCCESS 与 FAILED 任务再 cancel → 都不动")
    void cancel_respects_terminal_states() {
        // ⛔ 我第一版写成 `times(1)` —— 但 cancel 的守卫是 **SUCCESS || FAILED 都拒**，
        //    两次都该是 no-op。断言写错会让人以为"FAILED 还能被取消"（事实不允许）。
        givenTask(AgentTask.STATUS_SUCCESS);
        service.cancel(7L);
        verify(mapper, never()).updateById(any(AgentTask.class));

        givenTask(AgentTask.STATUS_FAILED);
        service.cancel(7L);
        verify(mapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    @DisplayName("cancel：RUNNING → CANCELLED")
    void cancel_marks_cancelled() {
        givenTask(AgentTask.STATUS_RUNNING);

        service.cancel(7L);

        assertThat(capturedUpdate().getStatus()).isEqualTo(AgentTask.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("succeed：写入产物引用与 token 用量，并记 agent.task{status=success} 指标")
    void succeed_records_result_and_metric() {
        givenTask(AgentTask.STATUS_RUNNING);

        service.succeed(7L, "result-abc", 1234L);

        AgentTask updated = capturedUpdate();
        assertThat(updated.getStatus()).isEqualTo(AgentTask.STATUS_SUCCESS);
        assertThat(updated.getResultRef()).isEqualTo("result-abc");
        assertThat(updated.getTokenUsage()).isEqualTo(1234L);
        assertThat(meters.counter("agent.task", "status", "success").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("fail：错误信息**截断到 500 字**（DB 列宽有限，超长会写库失败）")
    void fail_truncates_long_error() {
        givenTask(AgentTask.STATUS_RUNNING);
        String longMsg = "x".repeat(900);

        service.fail(7L, "E1", longMsg);

        assertThat(capturedUpdate().getErrorMsg()).hasSize(500);
    }

    @Test
    @DisplayName("补偿：把超时未心跳的 RUNNING/PENDING 标 FAILED（errorCode=TIMEOUT），并返回条数")
    void compensate_marks_stale_tasks_failed() {
        AgentTask running = existing(AgentTask.STATUS_RUNNING);
        when(mapper.selectList(any())).thenReturn(new java.util.ArrayList<>(List.of(running)));

        int marked = service.compensateStaleTasks();

        assertThat(marked).isEqualTo(1);
        assertThat(running.getStatus()).as("崩溃的任务不能一直'执行中'").isEqualTo(AgentTask.STATUS_FAILED);
        assertThat(running.getErrorCode()).isEqualTo("TIMEOUT");
        verify(mapper).updateById(running);
    }

    @Test
    @DisplayName("补偿：没有陈旧任务时不写库、不误报")
    void compensate_noop_when_nothing_stale() {
        when(mapper.selectList(any())).thenReturn(List.of());
        assertThat(service.compensateStaleTasks()).isZero();
        verify(mapper, never()).updateById(any(AgentTask.class));
    }

    @Test
    @DisplayName("任务不存在：各状态操作都**静默返回**（补偿扫描会碰到已被清理的 id）")
    void missing_task_is_noop() {
        when(mapper.selectById(any())).thenReturn(null);
        service.start(99L);
        service.fail(99L, "E", "m");
        service.cancel(99L);
        service.succeed(99L, "r", 0L);
        verify(mapper, never()).updateById(any(AgentTask.class));
    }
}
