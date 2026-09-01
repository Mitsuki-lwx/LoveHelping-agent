package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import cn.lwx.lwxaiagent.infrastructure.observability.LangfuseReporter;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务编排图执行门面（ADR-19）：统一异步执行入口 + agent 路径的记忆承接。
 * <ul>
 *   <li><b>执行</b>：图 invoke（线程池异步），返回 OUTPUT / ADVICE_TIERS / TOOL_EVENTS / 路由</li>
 *   <li><b>历史注入</b>：执行前把 message 表窗口历史 + 本条用户消息放入 {@code MESSAGES}
 *       （普通/简答/沙盘路径节点自管记忆，不读此键；agent 工具循环读取实现多轮上下文）</li>
 *   <li><b>落库</b>：仅 agent 路径完成后再写回 message 表（普通等由 ChatExecutor advisor 落库，
 *       避免重复）；工具消息不过滤进 message 表</li>
 *   <li><b>stop</b>：取消活跃异步执行（对应旧 AgentLoopExecutor.stop）</li>
 * </ul>
 */
@Slf4j
@Component
public class GraphRunner {

    /** 与 OrchestrationGraph.classify 写入的路由键一致 */
    static final String ROUTE_KEY = "graph.route";
    static final String ROUTE_AGENT = "agent";
    static final String ROUTE_VISION = "vision";

    private final CompiledGraph graph;
    private final ChatMemoryFactory chatMemoryFactory;
    private final GraphObservability observability;
    private final org.springframework.beans.factory.ObjectProvider<LangfuseReporter> langfuseReporter;
    private final io.micrometer.tracing.Tracer tracer;
    private final ConcurrentHashMap<String, CompletableFuture<?>> activeRuns = new ConcurrentHashMap<>();

    public GraphRunner(OrchestrationGraph orchestrationGraph,
                       ChatMemoryFactory chatMemoryFactory,
                       GraphObservability observability,
                       org.springframework.beans.factory.ObjectProvider<LangfuseReporter> langfuseReporter,
                       io.micrometer.tracing.Tracer tracer) throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        this.graph = orchestrationGraph.compile();
        this.chatMemoryFactory = chatMemoryFactory;
        this.observability = observability;
        this.langfuseReporter = langfuseReporter;
        this.tracer = tracer;
    }

    /** 异步执行一轮图（线程池内阻塞调用模型）。 */
    public CompletableFuture<Map<String, Object>> runAsync(Map<String, Object> input, String threadId) {
        injectHistory(input, threadId);
        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            // 全链路 trace 串联：恢复 HTTP 入口 span 的 trace 上下文（异步线程丢失请求作用域，
            // 导致 LLM/embedding generation 变成孤立 root trace——见 2026-08-31 全链路测评）
            io.micrometer.tracing.Span pipelineSpan = startPipelineSpan(input, threadId);
            try (io.micrometer.tracing.Tracer.SpanInScope ws = tracer.withSpan(pipelineSpan)) {
                RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
                long t0 = System.currentTimeMillis();
                OverAllState result = graph.invoke(input, config).get();
                Map<String, Object> out = extract(result);
                // agent / 视觉 路径由 GraphRunner 统一落库（普通/简答/沙盘由 ChatExecutor advisor 落库，避免重复）
                if (ROUTE_AGENT.equals(out.get(ROUTE_KEY)) || ROUTE_VISION.equals(out.get(ROUTE_KEY))) {
                    persistConversation(threadId, input, out);
                }
                // 图 trace（CAP-7）：路径 + 路由 + 总耗时
                @SuppressWarnings("unchecked")
                List<String> path = (List<String>) out.getOrDefault(GraphStateKeys.GRAPH_PATH, List.of());
                long totalMs = System.currentTimeMillis() - t0;
                observability.traceLog(String.valueOf(out.get(ROUTE_KEY)), path, totalMs);
                // Langfuse 上报（参考 CodeForge：手写 2.x 签名 ingestion；fire-and-forget）
                LangfuseReporter reporter = langfuseReporter.getIfAvailable();
                if (reporter != null) {
                    String msg = input.get(GraphStateKeys.MESSAGE) != null ? input.get(GraphStateKeys.MESSAGE).toString() : "";
                    reporter.report(threadId, String.valueOf(out.get(ROUTE_KEY)), msg,
                            String.valueOf(out.getOrDefault(GraphStateKeys.OUTPUT, "")), totalMs, null);
                }
                return out;
            } catch (Exception e) {
                pipelineSpan.error(e);
                throw new IllegalStateException("OrchestrationGraph run failed", e);
            } finally {
                pipelineSpan.end();
            }
        });
        activeRuns.put(threadId, future);
        future.whenComplete((r, ex) -> activeRuns.remove(threadId));
        return future;
    }

    /** 图执行根 span：有透传上下文则挂到 HTTP 入口 trace 下（跨线程父子串联），否则独立 root */
    private io.micrometer.tracing.Span startPipelineSpan(Map<String, Object> input, String threadId) {
        var builder = tracer.spanBuilder().name("chat.pipeline").tag("chat.id", threadId);
        Object tid = input.get(GraphStateKeys.PIPELINE_TRACE_ID);
        Object sid = input.get(GraphStateKeys.PIPELINE_SPAN_ID);
        if (tid instanceof String t && sid instanceof String s) {
            var parentCtx = tracer.traceContextBuilder()
                    .traceId(t).spanId(s).sampled(Boolean.TRUE).build();
            builder.setParent(parentCtx);
        }
        return builder.start();
    }

    /** 同步执行（测试/内部调用用） */
    public Map<String, Object> run(Map<String, Object> input, String threadId) {
        return runAsync(input, threadId).join();
    }

    /** 取消活跃执行（对应旧 AgentLoopExecutor.stop） */
    public void stop(String threadId) {
        CompletableFuture<?> f = activeRuns.remove(threadId);
        if (f != null) {
            f.cancel(true);
            log.info("GraphRunner stopped: {}", threadId);
        }
    }

    // ==================== 内部 ====================

    private Map<String, Object> extract(OverAllState result) {
        Map<String, Object> out = new HashMap<>();
        out.put(ROUTE_KEY, result.value(ROUTE_KEY).orElse(null));
        out.put(GraphStateKeys.OUTPUT, result.value(GraphStateKeys.OUTPUT).orElse(""));
        out.put(GraphStateKeys.ADVICE_TIERS, result.value(GraphStateKeys.ADVICE_TIERS).orElse(null));
        Object path = result.value(GraphStateKeys.GRAPH_PATH).orElse(null);
        out.put(GraphStateKeys.GRAPH_PATH, path instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of());
        Object tools = result.value(GraphStateKeys.TOOL_EVENTS).orElse(null);
        out.put(GraphStateKeys.TOOL_EVENTS, tools instanceof List<?> l
                ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of());
        return out;
    }

    /** agent 路径多轮上下文：message 表窗口 + 本条用户消息 → MESSAGES（对其他路径无副作用） */
    private void injectHistory(Map<String, Object> input, String threadId) {
        if (input.containsKey(GraphStateKeys.MESSAGES)) return;
        try {
            List<Message> window = chatMemoryFactory.create().get(threadId);
            List<Message> msgs = new ArrayList<>(window == null ? new ArrayList<>() : window);
            Object msg = input.get(GraphStateKeys.MESSAGE);
            if (msg != null) {
                msgs.add(new UserMessage(msg.toString()));
            }
            input.put(GraphStateKeys.MESSAGES, msgs);
        } catch (Exception e) {
            log.warn("GraphRunner history inject failed ({}): {}", threadId, e.getMessage());
        }
    }

    /** agent 路径落库：仅 user + 最终 assistant（工具消息不进 message 表） */
    private void persistConversation(String threadId, Map<String, Object> input, Map<String, Object> out) {
        try {
            List<Message> toPersist = new ArrayList<>();
            Object msg = input.get(GraphStateKeys.MESSAGE);
            if (msg != null) {
                toPersist.add(new UserMessage(msg.toString()));
            }
            Object output = out.get(GraphStateKeys.OUTPUT);
            if (output != null && !output.toString().isBlank()) {
                toPersist.add(new AssistantMessage(output.toString()));
            }
            if (!toPersist.isEmpty()) {
                chatMemoryFactory.create().add(threadId, toPersist);
            }
        } catch (Exception e) {
            log.warn("GraphRunner agent persist failed ({}): {}", threadId, e.getMessage());
        }
    }
}