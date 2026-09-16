package cn.lwx.lwxaiagent.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCircuitTest {

    /** 生产形状（ADR-32 默认值）。 */
    private static LlmGatewayProperties.Circuit production() {
        return new LlmGatewayProperties.Circuit();
    }

    private static LlmGatewayProperties.Circuit window(int size, int minimumCalls, double rate, int halfOpenProbes) {
        var config = new LlmGatewayProperties.Circuit();
        config.setSlidingWindowSize(size);
        config.setMinimumNumberOfCalls(minimumCalls);
        config.setFailureRateThreshold(rate);
        config.setHalfOpenProbes(halfOpenProbes);
        return config;
    }

    @Test void halfOpenAllowsOneProbeAndLateOldSuccessCannotCloseCircuit() {
        AtomicLong clock = new AtomicLong();
        var config = window(1, 1, 1.0, 1);
        config.setOpenMs(10);
        config.setMaxOpenMs(10);
        var c = new ProviderCircuit(config, clock::get);
        var slow = c.acquire();
        c.acquire().failure();
        slow.success();
        assertNull(c.acquire());
        clock.set(11_000_000);
        var probe = c.acquire();
        assertNotNull(probe);
        assertNull(c.acquire());
        probe.success();
        assertNotNull(c.acquire());
    }

    @Test void cancelledProbeCannotLeaveCircuitStuckHalfOpen() {
        AtomicLong clock = new AtomicLong();
        var config = window(1, 1, 1.0, 1);
        config.setOpenMs(10);
        config.setMaxOpenMs(10);
        var c = new ProviderCircuit(config, clock::get);
        c.acquire().failure();
        clock.set(11_000_000);
        c.acquire().cancel();
        assertNull(c.acquire());
        clock.set(22_000_000);
        assertNotNull(c.acquire());
    }

    /**
     * S10 回归锁：厂商个位数百分比的背景拒绝（经重试放大后仍远低于阈值）不得打开熔断。
     * 旧实现下这里会在第 5 次连续失败时打开，把一次瞬时限流放大成全量降级。
     */
    @Test void backgroundNoiseBelowFailureRateNeverOpensCircuit() {
        var c = new ProviderCircuit(production());
        // 每 20 次调用里 4 次失败 = 20% 失败率，低于 50% 阈值。
        for (int i = 0; i < 200; i++) {
            var ticket = c.acquire();
            assertNotNull(ticket, "第 " + i + " 次调用不应被熔断拒绝");
            if (i % 5 == 0) ticket.failure(); else ticket.success();
        }
        assertFalse(c.isOpen());
        assertEquals(0.2, c.failureRate(), 0.001);
    }

    /** 真故障（失败率趋近 100%）必须在样本够量后立刻打开。 */
    @Test void sustainedFailureOpensAfterMinimumSamples() {
        var c = new ProviderCircuit(production());
        for (int i = 0; i < 19; i++) {
            var ticket = c.acquire();
            assertNotNull(ticket, "样本未达 minimumNumberOfCalls 前不判定");
            ticket.failure();
        }
        assertFalse(c.isOpen(), "19 < minimumNumberOfCalls(20) 时不应打开");
        c.acquire().failure();
        assertTrue(c.isOpen(), "样本够量且失败率 100% >= 50% 时应打开");
        assertNull(c.acquire(), "打开后应快速失败");
    }

    /** 半开允许多个并发探针：任一成功即闭合，且退避复位。 */
    @Test void halfOpenProbesRunConcurrentlyAndFirstSuccessCloses() {
        AtomicLong clock = new AtomicLong();
        var config = window(20, 20, 1.0, 3);
        config.setOpenMs(10);
        config.setMaxOpenMs(10);
        var c = new ProviderCircuit(config, clock::get);
        for (int i = 0; i < 20; i++) c.acquire().failure();
        assertTrue(c.isOpen());
        clock.set(11_000_000);
        var first = c.acquire();
        var second = c.acquire();
        var third = c.acquire();
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertNull(c.acquire(), "探针数超过 halfOpenProbes 后不再放行");
        first.success();
        assertNotNull(c.acquire(), "任一探针成功即恢复");
        assertFalse(c.isOpen());
    }

    /** 探针全部失败 → 打开时长指数退避，并封顶在 maxOpenMs。 */
    @Test void repeatedProbeFailureBacksOffUpToMaxOpenMs() {
        AtomicLong clock = new AtomicLong();
        var config = window(1, 1, 1.0, 1);
        config.setOpenMs(10);
        config.setMaxOpenMs(40);
        var c = new ProviderCircuit(config, clock::get);
        c.acquire().failure();                 // 打开，openedAt = 0，退避基数 10ms

        clock.set(11_000_000);                 // 距打开 11ms >= 10ms
        var p1 = c.acquire();
        assertNotNull(p1);
        p1.failure();                          // 探针失败 → 退避翻倍到 20ms，openedAt = 11ms

        clock.set(21_000_000);                 // 距上次仅 10ms < 20ms
        assertNull(c.acquire());

        clock.set(32_000_000);                 // 距上次 21ms >= 20ms
        var p2 = c.acquire();
        assertNotNull(p2);
        p2.failure();                          // → 退避翻倍到 40ms，openedAt = 32ms

        clock.set(52_000_000);                 // 距上次 20ms < 40ms
        assertNull(c.acquire());

        clock.set(73_000_000);                 // 距上次 41ms >= 40ms
        var p3 = c.acquire();
        assertNotNull(p3);
        p3.failure();                          // → 封顶 40ms，openedAt = 73ms

        clock.set(112_000_000);                // 距上次 39ms < 40ms（未继续放大）
        assertNull(c.acquire());
        clock.set(114_000_000);                // 距上次 41ms >= 40ms
        assertNotNull(c.acquire());
    }

    /** 取消不算供应商失败：不清窗、不计失败率。 */
    @Test void cancellationIsNotProviderEvidence() {
        var c = new ProviderCircuit(production());
        for (int i = 0; i < 30; i++) c.acquire().cancel();
        assertEquals(0, c.recordedSamples());
        assertEquals(0.0, c.failureRate(), 0.0);
        assertFalse(c.isOpen());
    }

    /** 本地重排器走连续计数语义（兼容入口），与旧行为等价。 */
    @Test void consecutiveModeStillOpensAfterNConsecutiveFailures() {
        var c = new ProviderCircuit(true, 3, 10);
        c.acquire().failure();
        c.acquire().failure();
        assertNotNull(c.acquire(), "2 次连续失败 < 3 不打开");
        c.acquire().failure();
        assertTrue(c.isOpen(), "3 次连续失败后应打开");
        assertNull(c.acquire(), "打开后快速失败");
    }
}
