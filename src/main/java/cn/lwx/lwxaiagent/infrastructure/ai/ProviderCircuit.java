package cn.lwx.lwxaiagent.infrastructure.ai;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * ADR-32 滑窗失败率熔断器（Resilience4j 模型），替代原"连续失败 N 次即打开"的实现。
 *
 * <p>为什么换掉连续计数：厂商在接近并发上限时会有个位数百分比的背景拒绝（实测 24 并发下
 * 小请求 3.4% / 大请求 5.8% / 流式 7.0%），叠加"每次失败尝试都单独计数"的重试放大后，
 * 连续 5 次失败极易凑齐——一次瞬时限流因此被放大成 15s 全量降级（实测占窗口 86%）。
 * 失败率判定对背景噪声天然免疫：7% 的噪声在 50 次滑窗里只贡献几个百分点，
 * 远低于 50% 阈值；而真正的供应商故障（失败率趋近 100%）会在样本够量后立刻打开。</p>
 *
 * <p>另外两点对齐头部实践：打开时长按"探针再失败"指数退避（上限 {@code maxOpenMs}），
 * 半开放多个并发探针（{@code halfOpenProbes}）——单探针会把恢复时间拉长一个数量级，
 * 多探针让"误开的熔断"在一轮内就恢复（噪声下 3 个探针全失败概率约 3e-4）。</p>
 *
 * <p>陈旧结果隔离（{@code epoch}/{@code generation}）语义保持不变：熔断状态一旦推进代次，
 * 在途的旧票据无论成败都不再影响状态。</p>
 */
public final class ProviderCircuit {
    private final boolean enabled;
    private final int minimumCalls;
    private final double failureRateThreshold;
    private final int halfOpenProbes;
    private final long baseOpenNanos;
    private final long maxOpenNanos;
    private final LongSupplier clock;

    /** 最近 {@code window.length} 次调用的结果环形缓冲，{@code true} 表示失败。 */
    private final boolean[] window;
    private int cursor;
    private int samples;
    private int failures;

    private long openNanos;
    private long openedAt;
    private long epoch;
    private boolean open;
    private int probesInFlight;

    public ProviderCircuit(LlmGatewayProperties.Circuit config) {
        this(config, System::nanoTime);
    }

    /**
     * 连续计数语义的入口（保留给本地重排器等确定性依赖）。
     *
     * <p>等价于 {@code window = minimumCalls = consecutiveFailures, failureRate = 1.0}：
     * 只有最近 N 次全是失败才打开。本地 cross-encoder 进程要么健康要么挂掉，
     * 不存在厂商式的个位数百分比背景拒绝，所以连续计数在这里仍然是合适策略；
     * 云端 LLM 请走 {@link #ProviderCircuit(LlmGatewayProperties.Circuit)}。</p>
     */
    public ProviderCircuit(boolean enabled, int consecutiveFailures, long openMs) {
        this(consecutive(enabled, consecutiveFailures, openMs), System::nanoTime);
    }

    private static LlmGatewayProperties.Circuit consecutive(boolean enabled, int consecutiveFailures, long openMs) {
        var config = new LlmGatewayProperties.Circuit();
        config.setEnabled(enabled);
        int n = Math.max(1, consecutiveFailures);
        config.setSlidingWindowSize(n);
        config.setMinimumNumberOfCalls(n);
        config.setFailureRateThreshold(1.0);
        config.setHalfOpenProbes(1);
        config.setOpenMs(openMs);
        config.setMaxOpenMs(openMs);
        return config;
    }

    ProviderCircuit(LlmGatewayProperties.Circuit config, LongSupplier clock) {
        this.enabled = config.isEnabled();
        this.window = new boolean[Math.max(1, config.getSlidingWindowSize())];
        this.minimumCalls = Math.max(1, Math.min(config.getMinimumNumberOfCalls(), window.length));
        this.failureRateThreshold = Math.max(0.0, Math.min(1.0, config.getFailureRateThreshold()));
        this.halfOpenProbes = Math.max(1, config.getHalfOpenProbes());
        this.baseOpenNanos = toNanos(config.getOpenMs());
        this.maxOpenNanos = Math.max(baseOpenNanos, toNanos(config.getMaxOpenMs()));
        this.openNanos = baseOpenNanos;
        this.clock = clock;
    }

    private static long toNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
    }

    /** null means fail fast. Tickets must finish on success, error, or cancellation. */
    public synchronized Ticket acquire() {
        if (!enabled) return new Ticket(epoch, false);
        if (open) {
            if (probesInFlight >= halfOpenProbes) return null;
            if (clock.getAsLong() - openedAt < openNanos) return null;
            probesInFlight++;
            return new Ticket(epoch, true);
        }
        return new Ticket(epoch, false);
    }

    synchronized boolean isOpen() {
        return open;
    }

    synchronized int recordedSamples() {
        return samples;
    }

    synchronized double failureRate() {
        return samples == 0 ? 0.0 : (double) failures / samples;
    }

    public final class Ticket {
        private final long generation;
        private final boolean probe;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Ticket(long generation, boolean probe) {
            this.generation = generation;
            this.probe = probe;
        }

        public void success() { finish(0); }

        public void failure() { finish(1); }

        public void cancel() { finish(2); }

        private void finish(int outcome) {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (ProviderCircuit.this) {
                if (!enabled || generation != epoch) return;
                if (probe) {
                    probesInFlight = Math.max(0, probesInFlight - 1);
                    if (outcome == 0) {
                        // 任一探针成功即认定供应商已恢复：闭合 + 清窗 + 退避复位。
                        open = false;
                        probesInFlight = 0;
                        openNanos = baseOpenNanos;
                        reset();
                        epoch++;
                    } else if (probesInFlight == 0) {
                        // 本轮探针全部未成功：重新打开。只有真实失败才退避，取消不算供应商证据。
                        openedAt = clock.getAsLong();
                        if (outcome == 1) openNanos = Math.min(maxOpenNanos, Math.max(baseOpenNanos, openNanos * 2));
                        epoch++;
                    }
                    return;
                }
                if (open) return;
                if (outcome == 0) {
                    record(false);
                } else if (outcome == 1) {
                    record(true);
                }
                // outcome == 2（取消）不入窗：被中断的调用不构成供应商证据。
            }
        }
    }

    private void record(boolean failure) {
        if (samples == window.length) {
            if (window[cursor]) failures--;
        } else {
            samples++;
        }
        window[cursor] = failure;
        if (failure) failures++;
        cursor = (cursor + 1) % window.length;
        if (samples >= minimumCalls && failureRate() >= failureRateThreshold) {
            open = true;
            openedAt = clock.getAsLong();
            epoch++;
        }
    }

    private void reset() {
        Arrays.fill(window, false);
        cursor = 0;
        samples = 0;
        failures = 0;
    }
}
