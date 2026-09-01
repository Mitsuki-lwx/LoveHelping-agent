package cn.lwx.lwxaiagent.infrastructure.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台调度预算组件（ADR-20）：调度器统一向本组件"问询"——
 * 能否执行（{@link #permitted}）、本轮允许处理几个（{@link #allowance}）、
 * 每次 LLM/embedding 调用前扣减（{@link #consume}）、轮次结束回报候选数（{@link #recordOutcome}）。
 *
 * <p>策略：master/单调度器开关、每分钟令牌桶、空转指数退避。
 * 一切自身异常 <b>fail-open</b>（放行 + WARN）——后台节流绝不阻断既有功能。</p>
 */
@Slf4j
@Component
public class SchedulerBudget {

    private final SchedulerProperties props;
    private final MeterRegistry meterRegistry;

    /** 每调度器状态：连续空转轮数（candidates=0） */
    private final Map<String, AtomicInteger> idleRounds = new ConcurrentHashMap<>();
    /** 每调度器状态：退避期内已跳过的触发次数 */
    private final Map<String, AtomicInteger> backoffSkips = new ConcurrentHashMap<>();
    /** 令牌桶：当前可用配额（按 elapsed 分钟补充，封顶 llmBudgetPerMinute） */
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());

    public SchedulerBudget(SchedulerProperties props, MeterRegistry meterRegistry) {
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.tokens.set(props.getLlmBudgetPerMinute());
    }

    /** 本轮是否允许执行（master 开关 + 单调度器开关；异常 fail-open） */
    public boolean permitted(String name) {
        try {
            if (!props.isMasterEnabled()) {
                skipped(name, "master-disabled");
                return false;
            }
            boolean enabled = spec(name).isEnabled();
            if (!enabled) {
                skipped(name, "scheduler-disabled");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("SchedulerBudget permitted() failed (fail-open): {}", e.getMessage());
            return true;
        }
    }

    /**
     * 空转退避：退避激活时按倍数丢弃触发（例如 4 倍退避 = 每 4 次触发只执行 1 次）。
     *
     * @return false = 本轮应跳过（退避中）
     */
    public boolean backoffAllowsRun(String name) {
        try {
            int multiplier = currentBackoffMultiplier(name);
            if (multiplier <= 1) {
                backoffSkips.remove(name);
                return true;
            }
            AtomicInteger skips = backoffSkips.computeIfAbsent(name, k -> new AtomicInteger());
            // 第 multiplier 次触发放行一次，其余跳过
            if (skips.incrementAndGet() >= multiplier) {
                skips.set(0);
                log.info("Scheduler backoff x{}: {} allowed to run this round", multiplier, name);
                return true;
            }
            skipped(name, "idle-backoff-x" + multiplier);
            return false;
        } catch (Exception e) {
            log.warn("SchedulerBudget backoffAllowsRun() failed (fail-open): {}", e.getMessage());
            return true;
        }
    }

    /**
     * 本轮允许处理的数量 = min(想处理的数量, 当前可用配额)。
     * 配额为 0 时返回 0（调用方本轮直接结束）。
     */
    public int allowance(String name, int want) {
        try {
            refill();
            long available = tokens.get();
            if (available <= 0) {
                log.info("Scheduler budget exhausted for {} (llm-budget-per-minute={})", name,
                        props.getLlmBudgetPerMinute());
                skipped(name, "budget-exhausted");
                return 0;
            }
            return (int) Math.min(want, available);
        } catch (Exception e) {
            log.warn("SchedulerBudget allowance() failed (fail-open): {}", e.getMessage());
            return want;
        }
    }

    /** 每次实际发生 LLM/embedding 调用前扣减配额 */
    public void consume(String name, int n) {
        try {
            refill();
            long left = tokens.updateAndGet(t -> Math.max(0, t - n));
            counter("scheduler.llm_calls", name).increment(n);
            if (left <= 0) {
                log.info("Scheduler budget exhausted after consume by {}", name);
            }
        } catch (Exception e) {
            log.warn("SchedulerBudget consume() failed (fail-open): {}", e.getMessage());
        }
    }

    /** 轮次结束回报：candidates=0 计空转；>0 复位退避 */
    public void recordOutcome(String name, int candidates, int processed, long elapsedMs) {
        try {
            AtomicInteger idle = idleRounds.computeIfAbsent(name, k -> new AtomicInteger());
            if (candidates <= 0) {
                idle.incrementAndGet();
            } else {
                idle.set(0);
            }
            counter("scheduler.candidates", name).increment(candidates);
            counter("scheduler.processed", name).increment(processed);
            counter("scheduler.round", name).increment();
            log.info("SCHED_ROUND name={} candidates={} processed={} elapsedMs={} backoff=x{}",
                    name, candidates, processed, elapsedMs, currentBackoffMultiplier(name));
        } catch (Exception e) {
            log.warn("SchedulerBudget recordOutcome() failed (fail-open): {}", e.getMessage());
        }
    }

    /** 当前退避倍数：连续空转 < threshold → 1；否则 2^(空转轮数-threshold)，封顶 max-multiplier */
    public int currentBackoffMultiplier(String name) {
        AtomicInteger idle = idleRounds.get(name);
        if (idle == null) {
            return 1;
        }
        int over = idle.get() - props.getIdleBackoff().getThreshold();
        if (over <= 0) {
            return 1;
        }
        int power = Math.min(over, 10);
        long m = 1L << power;
        return (int) Math.min(m, props.getIdleBackoff().getMaxMultiplier());
    }

    /** 单轮时间预算（毫秒）——调用方用 start 时间对比 */
    public long maxRunMs() {
        return props.getMaxRunMs();
    }

    // ==================== 内部 ====================

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsedMin = (now - last) / 60_000_000_000L;
        if (elapsedMin <= 0) {
            return;
        }
        if (lastRefillNanos.compareAndSet(last, now)) {
            long add = elapsedMin * props.getLlmBudgetPerMinute();
            tokens.updateAndGet(t -> Math.min(props.getLlmBudgetPerMinute(), t + add));
        }
    }

    private SchedulerProperties.SchedulerSpec spec(String name) {
        if ("reflect".equals(name)) {
            return props.getReflect();
        }
        return props.getExtract();
    }

    private io.micrometer.core.instrument.Counter counter(String metric, String name) {
        return meterRegistry.counter(metric, "name", name);
    }

    private void skipped(String name, String reason) {
        try {
            meterRegistry.counter("scheduler.skipped", "name", name, "reason", reason).increment();
            log.debug("Scheduler skipped: name={} reason={}", name, reason);
        } catch (Exception ignored) {
        }
    }
}
