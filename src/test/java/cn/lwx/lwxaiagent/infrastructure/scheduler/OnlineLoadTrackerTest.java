package cn.lwx.lwxaiagent.infrastructure.scheduler;

import cn.lwx.lwxaiagent.infrastructure.ai.CapacityLimitChanged;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线负载感知与"后台让路在线"单测（ADR-20 补强 + OWASP LLM10，2026-09-03）。
 */
class OnlineLoadTrackerTest {

    @Test
    void inflightGateAcceptsUpToLimit() {
        OnlineLoadTracker t = new OnlineLoadTracker(2, new SimpleMeterRegistry());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertFalse(t.enter()); // 超过并发上限 → 拒绝（调用方给友好提示）
        assertEquals(2, t.inFlight());
        t.exit();
        t.exit();
        assertEquals(0, t.inFlight());
    }

    @Test
    void exitNeverGoesNegative() {
        OnlineLoadTracker t = new OnlineLoadTracker(4, new SimpleMeterRegistry());
        t.exit(); // 未 enter 先 exit（防御）
        assertEquals(0, t.inFlight());
    }

    /**
     * ADR-32：准入天花板必须跟随网关的自适应上限。
     * 否则网关自适应收缩到 15 而准入仍放 24，多出的 9 个请求会被网关拒成 4003。
     */
    @Test
    void admissionCeilingFollowsAdaptiveGatewayLimit() {
        OnlineLoadTracker t = new OnlineLoadTracker(4, new SimpleMeterRegistry());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertEquals(4, t.inFlight());

        // 网关自适应收缩到 2：天花板立即下调（即便许可已全部借出）
        t.onCapacityLimitChanged(new CapacityLimitChanged(2));
        assertEquals(2, t.ceiling());
        assertEquals(2, t.maxInFlight());
        t.exit();
        t.exit();
        t.exit();
        t.exit();
        assertEquals(0, t.inFlight());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertFalse(t.enter()); // 天花板 2 → 只放 2 个

        // 网关回升到 4
        t.onCapacityLimitChanged(new CapacityLimitChanged(4));
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertFalse(t.enter());
    }

    @Test
    void ceilingIsClampedToConfiguredMaxAndAtLeastOne() {
        OnlineLoadTracker t = new OnlineLoadTracker(8, new SimpleMeterRegistry());
        t.onCapacityLimitChanged(new CapacityLimitChanged(100));
        assertEquals(8, t.ceiling(), "不得超过配置的 app.online.max-inflight");
        t.onCapacityLimitChanged(new CapacityLimitChanged(0));
        assertEquals(1, t.ceiling(), "地板为 1，避免自我饿死");
    }

    @Test
    void onlineActiveWithinWindow() {
        OnlineLoadTracker t = new OnlineLoadTracker(8, new SimpleMeterRegistry());
        assertFalse(t.isOnlineActive()); // 初始无活动
        t.enter();
        assertTrue(t.isOnlineActive());  // 有在途请求
        t.exit();
        assertTrue(t.isOnlineActive());  // 30s 窗口内仍视为活跃（兜底防漏回收）
    }

    @Test
    void schedulerYieldsToOnlineTraffic() {
        SchedulerProperties props = new SchedulerProperties();
        props.setYieldToOnline(true);
        props.setLlmBudgetPerMinute(30);
        OnlineLoadTracker online = new OnlineLoadTracker(8, new SimpleMeterRegistry());
        SchedulerBudget budget = new SchedulerBudget(props, new SimpleMeterRegistry(), online);

        assertEquals(5, budget.allowance("reflect", 5)); // 无在线负载 → 正常放行
        online.enter();                                  // 用户在等回答
        assertEquals(0, budget.allowance("reflect", 5)); // 后台让路
        online.exit();
        assertTrue(budget.allowance("reflect", 5) <= 5); // 让路后恢复（仍在窗口内可能为 0）
    }

    @Test
    void yieldDisabledKeepsSchedulerRunning() {
        SchedulerProperties props = new SchedulerProperties();
        props.setYieldToOnline(false); // 关掉让路（运维开关）
        props.setLlmBudgetPerMinute(30);
        OnlineLoadTracker online = new OnlineLoadTracker(8, new SimpleMeterRegistry());
        SchedulerBudget budget = new SchedulerBudget(props, new SimpleMeterRegistry(), online);

        online.enter();
        assertEquals(5, budget.allowance("reflect", 5)); // 不让路 → 照常放行
        online.exit();
    }

    @Test
    void budgetWorksWithoutOnlineTracker() {
        SchedulerProperties props = new SchedulerProperties();
        props.setLlmBudgetPerMinute(30);
        SchedulerBudget budget = new SchedulerBudget(props, new SimpleMeterRegistry(), null);
        assertEquals(5, budget.allowance("reflect", 5)); // 未装配在线感知时不阻塞后台
    }

    // ==================== Phase 6：有界排队准入（ADR-29） ====================

    @Test
    void admitWaitsForSlotThenSucceeds() throws Exception {
        OnlineLoadTracker t = new OnlineLoadTracker(1, 2000, 4, new SimpleMeterRegistry());
        assertTrue(t.enter()); // 占满唯一的额度

        java.util.concurrent.atomic.AtomicReference<OnlineLoadTracker.Admission> got =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        Thread waiter = new Thread(() -> { got.set(t.admit()); done.countDown(); });
        waiter.start();
        Thread.sleep(200);
        assertEquals(1, t.queueDepth(), "等待期间队列深度应为 1");

        t.exit(); // 腾出额度 → 应转给排队者

        assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS), "排队者应在额度释放后放行");
        assertTrue(got.get().admitted());
        assertTrue(got.get().queued(), "应有排队标记");
        assertTrue(got.get().waitedMs() >= 100, "应记录真实等待时长");
        assertEquals(1, t.inFlight(), "额度应已转移给排队者");
        t.exit();
        assertEquals(0, t.inFlight());
        assertEquals(0, t.queueDepth());
    }

    @Test
    void admitTimesOutWithReadableReason() {
        OnlineLoadTracker t = new OnlineLoadTracker(1, 150, 4, new SimpleMeterRegistry());
        assertTrue(t.enter());
        OnlineLoadTracker.Admission a = t.admit();
        assertFalse(a.admitted());
        assertEquals("wait_timeout", a.reason(), "到点未排到应给出可读原因");
        assertTrue(a.queued(), "等过就应标记 queued（供前端区分'等过'与'没排上'）");
        assertTrue(a.waitedMs() >= 120, "应记录等待时长，实际 " + a.waitedMs());
        t.exit();
    }

    @Test
    void admitRejectsImmediatelyWhenQueueIsFull() throws Exception {
        OnlineLoadTracker t = new OnlineLoadTracker(1, 3000, 1, new SimpleMeterRegistry());
        assertTrue(t.enter());

        Thread first = new Thread(t::admit); // 占满唯一等待位
        first.start();
        Thread.sleep(200);
        assertEquals(1, t.queueDepth());

        long t0 = System.nanoTime();
        OnlineLoadTracker.Admission second = t.admit();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertFalse(second.admitted());
        assertEquals("queue_full", second.reason());
        assertTrue(elapsedMs < 200, "队列已满应立即拒绝，不应排队，实际 " + elapsedMs + "ms");
        assertFalse(second.queued());

        t.exit();
        first.join(3000);
    }

    @Test
    void waitZeroDegradesToImmediateRejection() {
        OnlineLoadTracker t = new OnlineLoadTracker(1, 0, 4, new SimpleMeterRegistry());
        assertTrue(t.enter());
        OnlineLoadTracker.Admission a = t.admit();
        assertFalse(a.admitted());
        assertEquals("no_wait_configured", a.reason(), "wait-ms=0 应退化为立即拒绝（Phase 6 之前的行为）");
        assertFalse(a.queued());
        t.exit();
    }

    @Test
    void exitNeverReleasesBeyondCapacity() {
        OnlineLoadTracker t = new OnlineLoadTracker(2, new SimpleMeterRegistry());
        t.exit();
        t.exit();
        t.exit(); // 多余释放不得制造额外额度
        assertEquals(0, t.inFlight());
        assertTrue(t.enter());
        assertTrue(t.enter());
        assertFalse(t.enter(), "释放不得超出上限");
        t.exit();
        t.exit();
    }

    @Test
    void admitFastPathDoesNotQueueWhenSlotIsFree() {
        OnlineLoadTracker t = new OnlineLoadTracker(2, 3000, 8, new SimpleMeterRegistry());
        OnlineLoadTracker.Admission a = t.admit();
        assertTrue(a.admitted());
        assertFalse(a.queued(), "有空额度时不应走排队路径");
        assertEquals(0L, a.waitedMs());
        assertEquals(0, t.queueDepth());
        t.exit();
    }
}
