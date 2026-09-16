package cn.lwx.lwxaiagent.infrastructure.ai;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADR-32 AIMD 自适应并发闸门。
 *
 * <p>背景：厂商的并发/速率上限不是固定值——会随账号配额、机房、上游策略、同账号其他调用方
 * 而变化（实测同一账号在不同时段能容忍的并发在 16~28 之间漂移）。把闸门硬编码在某个实测值上，
 * 要么留不住余量（一到上限就被拒），要么浪费吞吐（值调小后长期跑不满）。</p>
 *
 * <p>做法即头部通行方案：不预测上限，让闸门自己收敛。
 * 收到限流信号（429 / 厂商限流码）→ <b>乘性收缩</b>（{@code ×decreaseFactor}，地板 {@code minConcurrentCalls}）；
 * 连续成功 {@code increaseAfterSuccesses} 次 → <b>加性回升</b> 1，天花板仍是配置的
 * {@code max-concurrent-calls}。这与 TCP 拥塞控制同源，也是 Netflix concurrency-limits /
 * AWS 侧自适应限流的标准形状：收敛快、超调小、稳态围绕可用容量小幅摆动。</p>
 *
 * <p>收缩用 {@link Semaphore#reducePermits(int)}，即使当前许可已全部借出也能立即生效
 * （内部计数转负，后续 {@code release} 先还债再加容量），因此不需要等长尾请求结束。</p>
 */
final class AdaptiveConcurrencyLimiter {
    /**
     * {@link Semaphore#reducePermits(int)} 是 protected，标准做法是子类化后暴露。
     * 非公平语义与改造前保持一致（只做 tryAcquire，不存在排队公平性问题）。
     */
    private static final class ResizableSemaphore extends Semaphore {
        ResizableSemaphore(int permits) { super(permits); }

        void shrink(int delta) { reducePermits(delta); }
    }

    private final ResizableSemaphore permits;
    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicInteger limit;
    private final boolean enabled;
    private final int max;
    private final int min;
    private final double decreaseFactor;
    private final int increaseAfterSuccesses;
    private int streak;

    AdaptiveConcurrencyLimiter(int maxConcurrentCalls, LlmGatewayProperties.Adaptive config) {
        this.max = Math.max(1, maxConcurrentCalls);
        this.enabled = config.isEnabled();
        // 配置的上下限互相矛盾时以 max 为准，保证 min <= max。
        this.min = Math.max(1, Math.min(config.getMinConcurrentCalls(), max));
        this.decreaseFactor = Math.max(0.1, Math.min(1.0, config.getDecreaseFactor()));
        this.increaseAfterSuccesses = Math.max(1, config.getIncreaseAfterSuccesses());
        this.permits = new ResizableSemaphore(max);
        this.limit = new AtomicInteger(max);
    }

    boolean tryAcquire() {
        if (!permits.tryAcquire()) return false;
        inflight.incrementAndGet();
        return true;
    }

    void release() {
        permits.release();
        inflight.decrementAndGet();
    }

    /** 当前目标并发（自适应收敛值），即闸门允许同时在途的上限。 */
    int limit() {
        return limit.get();
    }

    /** 上限（配置的 max-concurrent-calls），自适应只在其下方摆动。 */
    int max() {
        return max;
    }

    int inflight() {
        return Math.max(0, inflight.get());
    }

    /** 收到厂商限流信号：乘性收缩。返回 true 表示本次确实收缩了。 */
    synchronized boolean onThrottled() {
        streak = 0;
        if (!enabled) return false;
        int current = limit.get();
        if (current <= min) return false;
        int target = Math.max(min, Math.min(current - 1, (int) Math.floor(current * decreaseFactor)));
        int delta = current - target;
        limit.addAndGet(-delta);
        permits.shrink(delta);
        return true;
    }

    /** 一次成功调用：累计到阈值就加性回升 1。返回 true 表示本次确实回升了。 */
    synchronized boolean onSuccess() {
        if (!enabled) return false;
        if (++streak < increaseAfterSuccesses) return false;
        streak = 0;
        int current = limit.get();
        if (current >= max) return false;
        limit.incrementAndGet();
        permits.release();
        return true;
    }
}
