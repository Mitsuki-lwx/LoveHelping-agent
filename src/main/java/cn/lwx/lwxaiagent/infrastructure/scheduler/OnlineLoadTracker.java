package cn.lwx.lwxaiagent.infrastructure.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 * <p><b>并发闸门</b>：{@code app.online.max-inflight}（默认 8）限制同时在途的在线请求数——
 * 防止 LLM 被打满（LLM10 Unbounded Consumption：消耗失控既是可用性问题也是成本问题）。</p>
 *
 * <p>健壮性：SSE 订阅生命周期通过 Flux.doFinally 回收；即便漏回收（客户端断连未触发
 * 回调），"活跃判定"还叠加 {@code lastActivity} 时间窗（默认 30s）兜底，不会永久卡住后台。</p>
 */
@Slf4j
@Component
public class OnlineLoadTracker {

    /** 判定"在线活跃"的兜底时间窗：最后活动时间在此窗口内即视为活跃（秒） */
    private static final long ACTIVE_WINDOW_MS = 30_000L;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong lastActivityNanos = new AtomicLong(0);
    private final int maxInFlight;
    private final MeterRegistry meterRegistry;

    public OnlineLoadTracker(@org.springframework.beans.factory.annotation.Value("${app.online.max-inflight:8}") int maxInFlight,
                             MeterRegistry meterRegistry) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.meterRegistry = meterRegistry;
    }

    /**
     * 进入在线请求（申请并发额度）。
     * @return true = 放行；false = 在线并发已满（调用方应给用户友好提示，而非 5xx）
     */
    public boolean enter() {
        try {
            int cur = inFlight.incrementAndGet();
            touch();
            if (cur > maxInFlight) {
                exit(); // 立即回收本次占用
                meterRegistry.counter("online.inflight.rejected").increment();
                log.warn("Online inflight limit reached (max={}), rejecting request", maxInFlight);
                return false;
            }
            meterRegistry.counter("online.inflight.entered").increment();
            return true;
        } catch (Exception e) {
            log.warn("OnlineLoadTracker enter() failed (fail-open): {}", e.getMessage());
            return true;
        }
    }

    /** 在线请求结束（SSE 流完成/取消/异常） */
    public void exit() {
        try {
            int left = inFlight.updateAndGet(v -> v > 0 ? v - 1 : 0);
            touch();
            if (left < 0) {
                inFlight.set(0);
            }
        } catch (Exception e) {
            log.warn("OnlineLoadTracker exit() failed: {}", e.getMessage());
        }
    }

    /** 当前在途在线请求数 */
    public int inFlight() {
        return inFlight.get();
    }

    /** 在线是否活跃（有在途请求，或 30s 内有过活动） */
    public boolean isOnlineActive() {
        try {
            if (inFlight.get() > 0) {
                return true;
            }
            long last = lastActivityNanos.get();
            return last > 0 && (System.nanoTime() - last) < ACTIVE_WINDOW_MS * 1_000_000L;
        } catch (Exception e) {
            return false; // 异常不阻塞后台（fail-open）
        }
    }

    private void touch() {
        lastActivityNanos.set(System.nanoTime());
    }
}
