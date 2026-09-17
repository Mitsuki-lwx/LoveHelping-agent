package cn.lwx.lwxaiagent.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ADR-33 上游故障期日志限流（logback {@link TurboFilter}）。
 *
 * <p>背景：上游（DashScope 等）不可达时，若干<b>第三方</b> logger 每次失败都打完整堆栈。
 * 实测 30 分钟长压产出 1.78 GB（1.91 GB 文件 / 1600 万行，其中 <b>93.9% 是堆栈行</b>），
 * 单条错误约 70 行堆栈，速率 ≈3.6 GB/h —— 长时上游故障会打满磁盘。
 * 而同一失败我们在入口层已经处理（转 SSE error 事件 / 降级日志 / Prometheus 指标），
 * 因此这些第三方内部错误属于<b>已知重复噪音</b>。</p>
 *
 * <p>做法：<b>只对白名单 logger</b> 做"时间窗内最多放行 N 条"的限流。窗口内保留
 * 完整样本（含堆栈）供排障；窗口结束时对"被抑制条数"输出<b>一条汇总</b>，
 * 使排障者明确知道"有东西被压了、压了多少"，而不是静默丢失。
 * 白名单外的日志<b>完全不受影响</b>（直接放行）。</p>
 *
 * <p>回退：{@code app.logging.throttle.enabled=false} 即恢复原行为，无需回滚代码。</p>
 */
public class LogThrottleFilter extends TurboFilter {
    /** 汇总专用 logger 名：不在白名单内，因此不会递归触发限流。 */
    static final String SUMMARY_LOGGER = "app.log.throttled";

    private volatile boolean enabled = true;
    private volatile long windowMs = 10_000L;
    private volatile int maxPerWindow = 3;
    private volatile Set<String> loggers = Set.of();

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setWindowMs(long windowMs) {
        this.windowMs = Math.max(100L, windowMs);
    }

    public void setMaxPerWindow(int maxPerWindow) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
    }

    /** 逗号分隔的 logger 白名单（logback 以字符串注入）。 */
    public void setLoggers(String csv) {
        this.loggers = (csv == null || csv.isBlank())
                ? Set.of()
                : Arrays.stream(csv.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-throttle-summary");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::flushSummaries, windowMs, windowMs, TimeUnit.MILLISECONDS);
        }
        super.start();
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        super.stop();
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (!enabled) return FilterReply.NEUTRAL;
        String name = logger.getName();
        if (!loggers.contains(name)) return FilterReply.NEUTRAL; // 白名单外：零影响
        long bucket = System.currentTimeMillis() / windowMs;
        Window window = windows.computeIfAbsent(name, key -> new Window());
        synchronized (window) {
            if (window.bucket != bucket) {
                window.pendingSummary += window.suppressed; // 上一窗口的抑制数交给汇总线程
                window.suppressed = 0;
                window.emitted = 0;
                window.bucket = bucket;
            }
            if (window.emitted < maxPerWindow) {
                window.emitted++;
                return FilterReply.NEUTRAL; // 保留完整样本（含堆栈）
            }
            window.suppressed++;
            return FilterReply.DENY;
        }
    }

    private void flushSummaries() {
        if (!enabled) return;
        // 汇总线程同样负责窗口滚动：否则"上游恢复、不再有新日志"时，
        // 最后一个窗口的抑制数会永远不被结转、永远不输出。
        long currentBucket = System.currentTimeMillis() / windowMs;
        for (var entry : windows.entrySet()) {
            long suppressed;
            Window window = entry.getValue();
            synchronized (window) {
                if (window.bucket != currentBucket) {
                    window.pendingSummary += window.suppressed;
                    window.suppressed = 0;
                    window.emitted = 0;
                    window.bucket = currentBucket;
                }
                suppressed = window.pendingSummary;
                window.pendingSummary = 0;
            }
            if (suppressed <= 0) continue;
            try {
                // 经 logback LoggerContext 取 logger（不走 SLF4J，避免初始化期递归）
                if (getContext() instanceof ch.qos.logback.classic.LoggerContext logbackContext) {
                    logbackContext.getLogger(SUMMARY_LOGGER).warn(
                            "上游降级噪音抑制：logger={} 在最近 {}ms 内被抑制 {} 条"
                                    + "（每个窗口保留 {} 条完整样本；如需恢复全部日志，把 app.logging.throttle.enabled 设为 false）",
                            entry.getKey(), windowMs, suppressed, maxPerWindow);
                }
            } catch (Throwable ignored) {
                // 汇总失败绝不影响业务日志
            }
        }
    }

    /** 单 logger 的窗口状态（由自身锁保护）。 */
    private static final class Window {
        long bucket = Long.MIN_VALUE;
        int emitted;
        long suppressed;
        long pendingSummary;
    }
}
