package cn.lwx.lwxaiagent.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-34：装饰器应把父 trace 上下文从提交线程带进 {@code @Async} 执行线程。
 *
 * <p>用真实 OTel tracer（非 mock）—— 因为被测行为恰恰依赖 Micrometer 的"当前 span"
 * 在换线程后是否可见，mock 掉 tracer 就等于把要测的东西替换掉了。</p>
 */
class TraceContextTaskDecoratorTest {

    SdkTracerProvider provider;
    AiTelemetry telemetry;

    @BeforeEach
    void setup() {
        provider = SdkTracerProvider.builder().build();
        io.opentelemetry.api.trace.Tracer otel = provider.get("test");
        telemetry = new AiTelemetry(new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { }));
    }

    @AfterEach
    void cleanup() {
        provider.close();
    }

    /** V1/V2：异步线程里能读到父 traceId，且容器 span 挂在父 span 之下（改造前两者都拿不到）。 */
    @Test
    void parentContextSurvivesAsyncBoundary() throws Exception {
        Span parent = telemetry.start("parent-task", null);
        String parentTraceId = parent.context().traceId();
        String parentSpanId = parent.context().spanId();

        AtomicReference<TraceContext> capturedInWorker = new AtomicReference<>();
        AtomicReference<String> innerTraceId = new AtomicReference<>();
        Runnable decorated;
        try (var scope = telemetry.scope(parent)) {   // 模拟"提交线程正处于某个 span 内"
            decorated = new TraceContextTaskDecorator(telemetry, "task async")
                    .decorate(() -> {
                        capturedInWorker.set(telemetry.capture());
                        Span inner = telemetry.start("inner-work", telemetry.capture());
                        innerTraceId.set(inner.context().traceId());
                        inner.end();
                    });
        }

        runOnOtherThread(decorated);

        assertNotNull(capturedInWorker.get(), "异步线程里必须能读到父上下文（改造前为 null）");
        assertEquals(parentTraceId, capturedInWorker.get().traceId(), "必须落在同一条 trace 上");
        assertEquals(parentSpanId, capturedInWorker.get().parentId(), "容器 span 应挂在父 span 之下");
        assertEquals(parentTraceId, innerTraceId.get(), "任务内新建的 span 也必须在同一条 trace 上");
        parent.end();
    }

    /** V3：提交线程没有父上下文时，原样返回同一个 Runnable（零开销、零行为变化）。 */
    @Test
    void withoutParentContextTaskIsReturnedUntouched() {
        Runnable task = () -> { };
        assertSame(task, new TraceContextTaskDecorator(telemetry, "task async").decorate(task),
                "无父上下文时不得包装任务");
    }

    /** V4：任务结束后工作线程的当前 span 必须恢复（否则线程复用会串上下文）。 */
    @Test
    void scopeIsNotLeakedOnTheWorkerThread() throws Exception {
        Span parent = telemetry.start("parent-task", null);
        AtomicReference<TraceContext> afterRun = new AtomicReference<>();
        Runnable decorated;
        try (var scope = telemetry.scope(parent)) {
            decorated = new TraceContextTaskDecorator(telemetry, "task async").decorate(() -> { });
        }

        Thread worker = new Thread(() -> {
            decorated.run();
            afterRun.set(telemetry.capture());   // run 之后立刻读：应为 null
        });
        worker.start();
        worker.join(5000);

        assertNull(afterRun.get(), "任务结束后工作线程不得残留当前 span");
        assertNull(telemetry.capture(), "测试线程也不应受到影响");
        parent.end();
    }

    private static void runOnOtherThread(Runnable task) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        worker.start();
        assertTrue(done.await(5, TimeUnit.SECONDS), "异步任务未在 5s 内完成");
        if (failure.get() != null) fail("异步任务抛出异常: " + failure.get());
    }
}
