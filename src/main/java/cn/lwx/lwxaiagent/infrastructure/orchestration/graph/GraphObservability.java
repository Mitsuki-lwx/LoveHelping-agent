package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 图级可观测（ADR-19 CAP-6/7）：
 * <ul>
 *   <li>节点进出计时 → {@code orchestration.node.duration{node,route}} 直方图</li>
 *   <li>图路径 → 状态键 {@code graph.path}（节点序列），GraphRunner 完成时输出 trace 日志</li>
 *   <li>路由/简答命中分布 → {@code orchestration.route{route}}、{@code orchestration.simple.hit}</li>
 * </ul>
 * 由 OrchestrationGraph 的节点包装器统一挂载，各节点本体零侵入。
 */
@Slf4j
@Component
public class GraphObservability {

    private final io.micrometer.tracing.Tracer tracer;

    public GraphObservability(MeterRegistry meterRegistry, io.micrometer.tracing.Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /** 节点执行包装：路径记录 + trace 子 span + 计时 + 耗时指标；返回执行结果状态更新 */
    public Map<String, Object> execute(String node, OverAllState state, NodeActionLike action) {
        appendPath(state, node);
        // 全链路埋点：节点作为 chat.pipeline 的子 span（OTLP → Langfuse 可见每阶段耗时）
        io.micrometer.tracing.Span nodeSpan = tracer.nextSpan().name("graph.node." + node);
        long start = System.nanoTime();
        try (io.micrometer.tracing.Tracer.SpanInScope ws = tracer.withSpan(nodeSpan.start())) {
            return action.apply(state);
        } catch (RuntimeException e) {
            nodeSpan.error(e);
            throw e;
        } finally {
            nodeSpan.end();
            String route = state.value(OrchestrationGraph.ROUTE).map(Object::toString).orElse("unknown");
            try {
                Timer.builder("orchestration.node.duration")
                        .tag("node", node)
                        .tag("route", route)
                        .register(meterRegistry)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    /** 路由分布 */
    public void routeHit(String route) {
        try {
            meterRegistry.counter("orchestration.route", "route", route == null ? "unknown" : route).increment();
        } catch (Exception ignored) {}
    }

    /** 简单问题命中（CAP-2 最短路径） */
    public void simpleHit() {
        try {
            meterRegistry.counter("orchestration.simple.hit").increment();
        } catch (Exception ignored) {}
    }

    /** 图完成 trace 日志（路径 + 路由 + 总耗时） */
    public void traceLog(String route, List<String> path, long totalMs) {
        log.info("GRAPH_TRACE route={} path={} totalMs={}", route, path, totalMs);
    }

    private void appendPath(OverAllState state, String node) {
        try {
            List<String> path = new ArrayList<>();
            Object existing = state.value(GraphStateKeys.GRAPH_PATH).orElse(null);
            if (existing instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof String s) path.add(s);
                }
            }
            path.add(node);
            state.updateState(Map.of(GraphStateKeys.GRAPH_PATH, path));
        } catch (Exception ignored) {}
    }

    private final MeterRegistry meterRegistry;

    /** 与图节点动作等价的函数式接口（避免依赖 graph-core 类型边界） */
    @FunctionalInterface
    public interface NodeActionLike {
        Map<String, Object> apply(OverAllState state);
    }
}