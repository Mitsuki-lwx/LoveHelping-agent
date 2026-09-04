package cn.lwx.lwxaiagent.infrastructure.scheduler;

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
}
