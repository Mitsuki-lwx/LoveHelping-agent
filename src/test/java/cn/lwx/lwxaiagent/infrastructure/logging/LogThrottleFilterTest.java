package cn.lwx.lwxaiagent.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-33 日志限流单测：窗口配额、白名单隔离、回退开关、窗口滚动、抑制汇总。
 */
class LogThrottleFilterTest {

    private static final String NOISY = "org.springframework.ai.chat.model.MessageAggregator";
    private static final String OTHER = "cn.lwx.lwxaiagent.SomeBusinessLogger";

    private LogThrottleFilter filter(long windowMs, int maxPerWindow, boolean enabled) {
        var f = new LogThrottleFilter();
        f.setEnabled(enabled);
        f.setWindowMs(windowMs);
        f.setMaxPerWindow(maxPerWindow);
        f.setLoggers(NOISY);
        return f;
    }

    /** 白名单外的 logger 名会被 decide 直接放行；用独立 context 仅取 getName()。 */
    private FilterReply call(LogThrottleFilter f, String loggerName) {
        Logger lg = new LoggerContext().getLogger(loggerName);
        return f.decide(null, lg, Level.ERROR, "Aggregation Error", null, null);
    }

    @Test
    void allowsFirstNAndDeniesRestWithinWindow() {
        var f = filter(60_000, 3, true);
        for (int i = 1; i <= 3; i++) {
            assertEquals(FilterReply.NEUTRAL, call(f, NOISY), "窗口内第 " + i + " 条应放行（保留样本）");
        }
        for (int i = 4; i <= 8; i++) {
            assertEquals(FilterReply.DENY, call(f, NOISY), "第 " + i + " 条应被限流");
        }
    }

    @Test
    void nonWhitelistedLoggerIsNeverThrottled() {
        var f = filter(60_000, 1, true);
        for (int i = 1; i <= 50; i++) {
            assertEquals(FilterReply.NEUTRAL, call(f, OTHER), "白名单外的日志不得受任何影响");
        }
    }

    @Test
    void disabledFilterLetsEverythingThrough() {
        var f = filter(60_000, 1, false);
        for (int i = 1; i <= 50; i++) {
            assertEquals(FilterReply.NEUTRAL, call(f, NOISY), "enabled=false 应完全恢复原行为");
        }
    }

    @Test
    void emptyWhitelistThrottlesNothing() {
        var f = new LogThrottleFilter();
        f.setEnabled(true);
        f.setWindowMs(60_000);
        f.setMaxPerWindow(1);
        f.setLoggers("  ");
        for (int i = 1; i <= 20; i++) {
            assertEquals(FilterReply.NEUTRAL, call(f, NOISY), "白名单为空时不应限流任何日志");
        }
    }

    @Test
    void windowRolloverRestoresQuota() throws Exception {
        var f = filter(200, 2, true);
        assertEquals(FilterReply.NEUTRAL, call(f, NOISY));
        assertEquals(FilterReply.NEUTRAL, call(f, NOISY));
        assertEquals(FilterReply.DENY, call(f, NOISY), "本窗口配额已用完");
        Thread.sleep(260);
        assertEquals(FilterReply.NEUTRAL, call(f, NOISY), "进入新窗口后配额应恢复");
    }

    @Test
    void suppressedCountIsSummarisedAfterWindow() throws Exception {
        var ctx = new LoggerContext();
        var f = filter(200, 2, true);
        f.setContext(ctx);
        Logger summaryLogger = ctx.getLogger(LogThrottleFilter.SUMMARY_LOGGER);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        summaryLogger.addAppender(appender);
        f.start();
        try {
            call(f, NOISY);                               // 放行
            call(f, NOISY);                               // 放行
            for (int i = 0; i < 5; i++) call(f, NOISY);   // 5 条被抑制
            Thread.sleep(700);                            // 越过窗口，等汇总线程
            assertFalse(appender.list.isEmpty(), "窗口结束后应输出抑制汇总（否则排障者无从知晓被压了多少）");
            String msg = appender.list.get(appender.list.size() - 1).getFormattedMessage();
            assertTrue(msg.contains(NOISY), "汇总应含被限流的 logger 名，实际: " + msg);
            assertTrue(msg.contains("5"), "应报告 5 条被抑制，实际: " + msg);
        } finally {
            f.stop();
            summaryLogger.detachAppender(appender);
        }
    }
}
