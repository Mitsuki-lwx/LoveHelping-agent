package cn.lwx.lwxaiagent.infrastructure.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SchedulerBudget 纯单测（ADR-20）：开关 / 令牌桶配额 / 空转退避。
 * 无 Spring 上下文——调度预算逻辑必须可脱离容器验证。
 */
class SchedulerBudgetTest {

    private SchedulerBudget budget(SchedulerProperties props) {
        return new SchedulerBudget(props, new SimpleMeterRegistry(), null);
    }

    @Test
    void masterSwitchDisablesEverything() {
        SchedulerProperties p = new SchedulerProperties();
        p.setMasterEnabled(false);
        SchedulerBudget b = budget(p);
        assertFalse(b.permitted("reflect"));
        assertFalse(b.permitted("extract"));
    }

    @Test
    void perSchedulerSwitchOnlyAffectsItself() {
        SchedulerProperties p = new SchedulerProperties();
        p.getReflect().setEnabled(false);
        SchedulerBudget b = budget(p);
        assertFalse(b.permitted("reflect"));
        assertTrue(b.permitted("extract"));
    }

    @Test
    void allowanceIsCappedByAvailableTokens() {
        SchedulerProperties p = new SchedulerProperties();
        p.setLlmBudgetPerMinute(5);
        SchedulerBudget b = budget(p);
        assertEquals(5, b.allowance("reflect", 20));   // min(want, tokens)
        b.consume("reflect", 3);
        assertEquals(2, b.allowance("reflect", 20));
        b.consume("reflect", 2);
        assertEquals(0, b.allowance("reflect", 20));   // 配额耗尽 → 本轮 0
    }

    @Test
    void tokensRefillOverTimeButCapped() throws Exception {
        SchedulerProperties p = new SchedulerProperties();
        p.setLlmBudgetPerMinute(3);
        SchedulerBudget b = budget(p);
        b.consume("extract", 3);
        assertEquals(0, b.allowance("extract", 5));
        // 模拟时间流逝：把 lastRefillNanos 回拨 2 分钟 → 补 6，但封顶 3
        java.lang.reflect.Field f = SchedulerBudget.class.getDeclaredField("lastRefillNanos");
        f.setAccessible(true);
        f.set(b, new java.util.concurrent.atomic.AtomicLong(
                System.nanoTime() - 2 * 60_000_000_000L));
        assertEquals(3, b.allowance("extract", 5));
    }

    @Test
    void idleBackoffKicksInAfterThresholdAndResets() {
        SchedulerProperties p = new SchedulerProperties();
        p.getIdleBackoff().setThreshold(3);
        p.getIdleBackoff().setMaxMultiplier(8);
        SchedulerBudget b = budget(p);

        // 连续 3 轮空转 → 仍 x1；第 4 轮空转起退避 x2
        b.recordOutcome("reflect", 0, 0, 0);
        b.recordOutcome("reflect", 0, 0, 0);
        b.recordOutcome("reflect", 0, 0, 0);
        assertEquals(1, b.currentBackoffMultiplier("reflect"));
        b.recordOutcome("reflect", 0, 0, 0);
        assertEquals(2, b.currentBackoffMultiplier("reflect"));
        b.recordOutcome("reflect", 0, 0, 0);
        assertEquals(4, b.currentBackoffMultiplier("reflect"));

        // 有候选 → 立即复位 x1
        b.recordOutcome("reflect", 5, 5, 100);
        assertEquals(1, b.currentBackoffMultiplier("reflect"));
        assertTrue(b.backoffAllowsRun("reflect"));
    }

    @Test
    void backoffSkipsRoundsThenAllowsOne() {
        SchedulerProperties p = new SchedulerProperties();
        p.getIdleBackoff().setThreshold(1);
        SchedulerBudget b = budget(p);
        b.recordOutcome("reflect", 0, 0, 0);  // idle=1, over=0 → 仍 x1（宽限期）
        b.recordOutcome("reflect", 0, 0, 0);  // idle=2, over=1 → x2 退避
        assertEquals(2, b.currentBackoffMultiplier("reflect"));
        // x2 = 每 2 次触发执行 1 次：第一次跳过，第二次放行
        assertFalse(b.backoffAllowsRun("reflect"));
        assertTrue(b.backoffAllowsRun("reflect"));
    }

    @Test
    void backoffMultiplierCapped() {
        SchedulerProperties p = new SchedulerProperties();
        p.getIdleBackoff().setThreshold(1);
        p.getIdleBackoff().setMaxMultiplier(8);
        SchedulerBudget b = budget(p);
        for (int i = 0; i < 10; i++) {
            b.recordOutcome("extract", 0, 0, 0);
        }
        assertEquals(8, b.currentBackoffMultiplier("extract"));
    }
}
