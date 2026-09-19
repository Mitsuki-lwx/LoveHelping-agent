package cn.lwx.lwxaiagent.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import org.springframework.core.task.TaskDecorator;

/**
 * <h1>跨线程边界的父 trace 上下文传播（ADR-34）</h1>
 *
 * <p><b>问题</b>：{@code @Async} 后台任务在线程池里执行，Micrometer 的"当前 span"是基于
 * {@link ThreadLocal} 的，换线程即丢失。于是后台路径产生的模型调用拿不到父上下文，
 * {@link AiTelemetry#start} 会跳过 {@code setParent} 而<b>自成一个新根 trace</b>——
 * 平台侧就只能靠时间戳邻近去猜归属（实测：反思任务 span 里查不到自己的 {@code llm.attempt}）。</p>
 *
 * <p><b>做法</b>：把父上下文在<b>提交线程</b>捕获，在<b>执行线程</b>重新建立作用域。
 * 与 HTTP 入口的处理口径一致——那条路径靠 {@code ChatExecutor} 把
 * {@link AiTelemetry#PARENT_CONTEXT_KEY} 显式塞进 advisor 参数；这里靠 {@link TaskDecorator}
 * 显式传播。两者都是"显式边界传播"，不使用全局 hook（ADR-24）。</p>
 *
 * <p><b>无父上下文时零副作用</b>：若提交线程本来就没有 span（例如应用启动早期触发的任务），
 * {@link #decorate} 直接原样返回任务——不建 span、不改变任何行为。</p>
 */
public final class TraceContextTaskDecorator implements TaskDecorator {

    private final AiTelemetry telemetry;
    private final String spanName;

    /**
     * @param telemetry 遥测门面（捕获父上下文 / 建 span / 建作用域）
     * @param spanName  容器 span 的名称，用于在平台上标识"这是哪个异步任务"
     */
    public TraceContextTaskDecorator(AiTelemetry telemetry, String spanName) {
        this.telemetry = telemetry;
        this.spanName = spanName;
    }

    @Override
    public Runnable decorate(Runnable task) {
        TraceContext parent = telemetry.capture(); // 必须是【提交线程】的当前上下文
        if (parent == null) {
            return task;
        }
        return () -> {
            Span span = telemetry.start(spanName, parent);
            try (var ignored = telemetry.scope(span)) { // 令执行线程的 currentSpan = 本 span
                task.run();
            } finally {
                span.end();
            }
        };
    }
}
