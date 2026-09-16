package cn.lwx.lwxaiagent.infrastructure.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线负载感知（ADR-20 补强 + OWASP LLM10，2026-09-03）：
 * 记录"当前有多少在线聊天请求正在进行"，供后台调度让路判断。
 *
 * <p><b>为什么需要</b>：ADR-20 给后台调度加了自身的每分钟 LLM 预算，但后台并不感知
 * 在线负载——用户正在等回答时后台仍在消耗配额（9/1 实测 20 分钟 89 次 embedding），
 * 在线与后台争抢同一条 LLM 通道。本组件把"在线是否在忙"暴露给 {@link SchedulerBudget}。</p>
 *
 * <p><b>并发闸门</b>：{@code app.online.max-inflight} 限制同时在途的在线请求数——
 * 防止 LLM 被打满（LLM10 Unbounded Consumption：消耗失控既是可用性问题也是成本问题）。
 * 该上限必须 ≤ 上游厂商可承受的并发（2026-09-15 实测 glm-4-flash ≈ 24），
 * 否则超额会透传成厂商 429 并被重试放大；启动期由 {@code CapacityGuard} 校验。</p>
 *
 * <p><b>动态天花板（ADR-32，2026-09-16）</b>：{@code app.online.max-inflight} 现在只是**设计上限**，
 * 实际准入天花板由网关的 AIMD 自适应上限驱动（见 {@link #onCapacityLimitChanged}）——
 * 两层闸门必须用同一个收敛值，否则网关会把自己放行的请求拒成 4003。</p>
 *
 * <p><b>有界排队（Phase 6，ADR-29）</b>：闸门满时不再立即拒绝，而是在
 * {@code app.online.wait-ms} 内排队等待腾出的额度；等待者数量受
 * {@code app.online.queue-capacity} 限制（超出的立即拒绝，保护容器线程）。
 * 等待发生在准入阶段（订阅线程），<b>不占用图执行线程池</b>；到点仍未排到则明确拒绝
 * 并给出重试建议——即"排队代替硬拒，但绝不无限等待"。</p>
 *
 * <p>健壮性：SSE 订阅生命周期通过 Flux.doFinally 回收；即便漏回收（客户端断连未触发
 * 回调），"活跃判定"还叠加 {@code lastActivity} 时间窗（默认 30s）兜底，不会永久卡住后台。</p>
 */
@Slf4j
@Component
public class OnlineLoadTracker {

    /** 判定"在线活跃"的兜底时间窗：最后活动时间在此窗口内即视为活跃（秒） */
    private static final long ACTIVE_WINDOW_MS = 30_000L;

    private final AtomicLong lastActivityNanos = new AtomicLong(0);
    private final int maxInFlight;
    /** 有界等待上限（毫秒）；0 = 关闭排队（退化为立即拒绝，Phase 6 前的行为） */
    private final long waitMs;
    /** 等待者上限：超出直接拒绝，避免无界堆积阻塞容器线程 */
    private final int queueCapacity;
    /**
     * 动态天花板（ADR-32）：默认 = {@link #maxInFlight}，由网关的自适应上限驱动下调/回升。
     * 准入不得高于网关实际能跑的并发，否则多出的请求只会被网关拒成 4003。
     */
    private volatile int ceiling;
    /** 公平信号量：按到达顺序发放额度，避免高并发下的饥饿。容量可随天花板收缩/回升。 */
    private final ResizableSemaphore permits;
    private final AtomicInteger waiters = new AtomicInteger();
    private final MeterRegistry meterRegistry;

    /**
     * 可收缩的公平信号量。{@link Semaphore#reducePermits(int)} 是 protected，故子类化暴露。
     * 计数可转负——因此即便许可已全部借出，天花板下调也能立即生效（后续 release 先还债再加容量）。
     */
    private static final class ResizableSemaphore extends Semaphore {
        ResizableSemaphore(int permits, boolean fair) { super(permits, fair); }

        void shrink(int delta) { reducePermits(delta); }

        void grow(int delta) { release(delta); }
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OnlineLoadTracker(@Value("${app.online.max-inflight:8}") int maxInFlight,
                             @Value("${app.online.wait-ms:3000}") long waitMs,
                             @Value("${app.online.queue-capacity:24}") int queueCapacity,
                             MeterRegistry meterRegistry) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.waitMs = Math.max(0, waitMs);
        this.queueCapacity = Math.max(0, queueCapacity);
        this.ceiling = this.maxInFlight;
        this.permits = new ResizableSemaphore(this.maxInFlight, true);
        this.meterRegistry = meterRegistry;
        try {
            Gauge.builder("online.inflight.current", this, OnlineLoadTracker::inFlight)
                    .description("当前在途在线请求数").register(meterRegistry);
            Gauge.builder("online.queue.depth", waiters, AtomicInteger::get)
                    .description("当前排队等待额度的请求数").register(meterRegistry);
        } catch (RuntimeException ignored) {
            // 指标注册失败不影响准入本身
        }
    }

    /** 兼容构造（测试与旧装配用）：关闭排队、队列容量与闸门等同 */
    public OnlineLoadTracker(int maxInFlight, MeterRegistry meterRegistry) {
        this(maxInFlight, 0L, Math.max(1, maxInFlight), meterRegistry);
    }

    /**
     * ADR-32：跟随网关的自适应并发上限，动态调整准入天花板。
     *
     * <p>只下调到 {@code adaptive.min-concurrent-calls}、不上调到超过配置的
     * {@code app.online.max-inflight}（后者仍是设计上限）。</p>
     */
    @org.springframework.context.event.EventListener
    public synchronized void onCapacityLimitChanged(cn.lwx.lwxaiagent.infrastructure.ai.CapacityLimitChanged event) {
        int target = Math.max(1, Math.min(maxInFlight, event.limit()));
        int delta = target - ceiling;
        if (delta == 0) return;
        if (delta < 0) permits.shrink(-delta); else permits.grow(delta);
        ceiling = target;
        log.info("admission ceiling follows adaptive gateway limit: {} (configured max={})", target, maxInFlight);
    }

    /** 当前准入天花板（= min(配置上限, 网关自适应上限)） */
    public int ceiling() {
        return ceiling;
    }

    /** 一次准入的结果与拒绝原因（供调用方生成可读提示与指标） */
    public record Admission(boolean admitted, boolean queued, String reason, long waitedMs) {
        static final String OK = null;
    }

    /**
     * 立即尝试进入（不排队）——保留给需要"快速失败"语义的调用方与既有测试。
     * @return true = 放行；false = 在途已满（调用方应给用户友好提示，而非 5xx）
     */
    public boolean enter() {
        if (permits.tryAcquire()) {
            touch();
            metric("online.inflight.entered");
            return true;
        }
        metric("online.inflight.rejected");
        return false;
    }

    /**
     * 有界排队准入（Phase 6 主路径）：
     * <ol>
     *   <li>有额度 → 立即放行</li>
     *   <li>满 → 若等待者已超 {@code queue-capacity} 则立即拒绝（{@code queue_full}）</li>
     *   <li>否则最多等 {@code wait-ms}：排到即放行（{@code queued=true}）；
     *       到点未排到则拒绝（{@code wait_timeout}）</li>
     * </ol>
     * 无论放行还是拒绝，调用方都必须只在放行时配对调用 {@link #exit()}。
     */
    public Admission admit() {
        if (permits.tryAcquire()) {
            touch();
            metric("online.inflight.entered");
            return new Admission(true, false, null, 0L);
        }
        if (waitMs <= 0 || queueCapacity <= 0) {
            metric("online.inflight.rejected");
            return new Admission(false, false, "no_wait_configured", 0L);
        }
        int waiting = waiters.incrementAndGet();
        try {
            if (waiting > queueCapacity) {
                metric("online.inflight.queue_full");
                return new Admission(false, false, "queue_full", 0L);
            }
            metric("online.inflight.queued");
            long t0 = System.nanoTime();
            boolean got = permits.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
            if (got) {
                touch();
                metric("online.inflight.entered_after_wait");
                recordWait(waitedMs);
                return new Admission(true, true, null, waitedMs);
            }
            metric("online.inflight.wait_timeout");
            recordWait(waitedMs);
            return new Admission(false, true, "wait_timeout", waitedMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metric("online.inflight.interrupted");
            return new Admission(false, true, "interrupted", 0L);
        } finally {
            waiters.decrementAndGet();
        }
    }

    /** 在线请求结束（SSE 流完成/取消/异常） */
    public void exit() {
        try {
            if (permits.availablePermits() < ceiling) {
                permits.release();
            }
            touch();
        } catch (Exception e) {
            log.warn("OnlineLoadTracker exit() failed: {}", e.getMessage());
        }
    }

    /** 当前在途在线请求数（相对动态天花板口径） */
    public int inFlight() {
        int used = ceiling - permits.availablePermits();
        return Math.max(0, Math.min(ceiling, used));
    }

    /** 当前排队等待者数量 */
    public int queueDepth() {
        return waiters.get();
    }

    /** 准入天花板（动态；排队长度估算与用户提示用） */
    public int maxInFlight() {
        return ceiling;
    }

    /** 有界等待上限（毫秒） */
    public long waitMs() {
        return waitMs;
    }

    // ==================== 平均单请求耗时（EMA，排队等待估算用，2026-09-07） ====================
    /** 平均完成时长 EMA（毫秒）。无样本时为 0 → 调用方用经验兜底值 */
    private volatile double avgDurationMs = 0.0;
    private static final double EMA_ALPHA = 0.2;

    /** 请求完成时更新 EMA（enter 与 exit 跨线程，时长由调用方显式传入） */
    public synchronized void recordDuration(long durationMs) {
        if (durationMs <= 0) return;
        double cur = avgDurationMs;
        avgDurationMs = cur == 0.0 ? durationMs : (EMA_ALPHA * durationMs + (1 - EMA_ALPHA) * cur);
    }

    /** 平均单请求耗时（毫秒）；无样本返回 0 */
    public double avgDurationMs() {
        return avgDurationMs;
    }

    /** 在线是否活跃（有在途请求，或 30s 内有过活动） */
    public boolean isOnlineActive() {
        try {
            if (inFlight() > 0) {
                return true;
            }
            long last = lastActivityNanos.get();
            return last > 0 && (System.nanoTime() - last) < ACTIVE_WINDOW_MS * 1_000_000L;
        } catch (Exception e) {
            return false; // 异常不阻塞后台（fail-open）
        }
    }

    private void metric(String name) {
        try { meterRegistry.counter(name).increment(); } catch (RuntimeException ignored) { }
    }

    private void recordWait(long waitedMs) {
        try {
            Timer.builder("online.inflight.wait_ms")
                    .description("有界排队等待时长（成功排到与超时都记录）")
                    .register(meterRegistry)
                    .record(waitedMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // 指标失败不影响准入
        }
    }

    private void touch() {
        lastActivityNanos.set(System.nanoTime());
    }
}
