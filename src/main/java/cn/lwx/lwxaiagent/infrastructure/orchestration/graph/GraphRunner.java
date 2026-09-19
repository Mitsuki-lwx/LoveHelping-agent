package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Interruptible graph execution, per-session single-flight and request-scoped tracing (ADR-23/24). */
@Slf4j
@Component
public class GraphRunner {
    static final String ROUTE_KEY = "graph.route";
    private final CompiledGraph graph;
    private final ChatMemoryFactory memory;
    private final GraphObservability observability;
    private final Tracer tracer;
    private final Executor executor;
    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    public GraphRunner(OrchestrationGraph orchestrationGraph, ChatMemoryFactory memory,
                       GraphObservability observability, Tracer tracer,
                       @Qualifier("graphExecutor") Executor executor) throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        this.graph = orchestrationGraph.compile();
        this.memory = memory;
        this.observability = observability;
        this.tracer = tracer;
        this.executor = executor;
    }

    public CompletableFuture<Map<String, Object>> runAsync(Map<String, Object> input, String threadId) {
        Map<String, Object> snapshot = new HashMap<>(input);
        ActiveRun run = new ActiveRun(threadId, snapshot);
        if (activeRuns.putIfAbsent(threadId, run) != null)
            throw new BizException(409, "当前会话仍有请求处理中，请等待完成或先停止");
        try { executor.execute(run.task); }
        catch (RejectedExecutionException rejected) {
            run.cancel();
            throw new BizException(4003, "系统繁忙，请稍后再试", Map.of("retryAfterSec", 2));
        }
        return run.result;
    }

    public Map<String, Object> run(Map<String, Object> input, String threadId) {
        CompletableFuture<Map<String, Object>> future = runAsync(input, threadId);
        try { return future.get(); }
        catch (InterruptedException e) { stop(threadId, future); Thread.currentThread().interrupt(); throw new CancellationException("Graph cancelled"); }
        catch (ExecutionException e) { throw new CompletionException(e.getCause()); }
    }

    public void stop(String threadId) { ActiveRun run = activeRuns.get(threadId); if (run != null) run.cancel(); }
    public void stop(String threadId, CompletableFuture<?> expected) {
        ActiveRun run = activeRuns.get(threadId);
        if (run != null && run.result == expected) run.cancel();
    }
    public int activeCount() { return activeRuns.size(); }

    private final class ActiveRun {
        final String key;
        final CompletableFuture<Map<String, Object>> result = new CompletableFuture<>();
        final AtomicInteger stage = new AtomicInteger(); // queued -> executing -> physically finished
        final FutureTask<Void> task;
        ActiveRun(String key, Map<String, Object> input) {
            this.key = key;
            this.task = new FutureTask<>(() -> {
                if (!stage.compareAndSet(0, 1)) return null;
                try {
                    if (!result.isCancelled()) result.complete(execute(input, key));
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                } finally {
                    stage.set(2);
                    activeRuns.remove(key, this); // no old completion can remove a later run
                }
                return null;
            });
            result.whenComplete((out, error) -> { if (result.isCancelled()) cancel(); });
        }
        void cancel() {
            task.cancel(true); // FutureTask interrupts the actual worker, unlike CompletableFuture.cancel alone.
            if (stage.compareAndSet(0, 2)) activeRuns.remove(key, this);
            result.cancel(false);
            // An executing run stays registered until its finally block, fencing uninterruptible old work.
        }
    }

    private Map<String, Object> execute(Map<String, Object> input, String threadId) throws Exception {
        // 跨线程边界：本方法跑在 graph-* 线程池上，本线程的 ThreadLocal 不是请求线程的那一份，
        // 故先快照、finally 中还原（TenantContext 的跨线程规则，2026-09-19）。
        TenantContext.Snapshot previous = TenantContext.capture();
        String user = Objects.toString(input.get(GraphStateKeys.USER_ID), "anonymous");
        Span span = pipelineSpan(input, threadId);
        long start = System.nanoTime();
        try (var ignored = tracer.withSpan(span)) {
            TenantContext.set("default", user, "USER");
            // Persist only serializable identifiers; each node restores this parent explicitly.
            input.put(GraphStateKeys.PIPELINE_TRACE_ID, span.context().traceId());
            input.put(GraphStateKeys.PIPELINE_SPAN_ID, span.context().spanId());
            input.put(GraphStateKeys.PIPELINE_SAMPLED, Boolean.TRUE.equals(span.context().sampled()));
            injectHistory(input, threadId);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
            OverAllState state = graph.invoke(input, config).orElseThrow(() -> new IllegalStateException("Empty graph state"));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Map<String, Object> output = extract(state);
            String route = Objects.toString(output.get(ROUTE_KEY), "unknown");
            if (Set.of("agent", "vision").contains(route)) persist(threadId, input, output);
            span.tag("graph.route", route).tag("graph.outcome", "success");
            @SuppressWarnings("unchecked") List<String> path = (List<String>) output.get(GraphStateKeys.GRAPH_PATH);
            observability.traceLog(route, path, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return output;
        } catch (Exception error) {
            boolean cancelled = error instanceof InterruptedException || error instanceof CancellationException || Thread.currentThread().isInterrupted();
            span.tag("graph.outcome", cancelled ? "cancelled" : "error");
            span.tag("error.category", AiTelemetry.failureCategory(error));
            if (!cancelled) {
                span.tag("langfuse.observation.level", "ERROR");
                span.error(new IllegalStateException("Graph execution failed"));
            }
            throw error;
        } finally {
            TenantContext.restore(previous);
            span.end();
        }
    }

    private Span pipelineSpan(Map<String, Object> input, String threadId) {
        var builder = tracer.spanBuilder().name("chat.pipeline");
        Object tid = input.get(GraphStateKeys.PIPELINE_TRACE_ID), sid = input.get(GraphStateKeys.PIPELINE_SPAN_ID);
        if (tid instanceof String t && sid instanceof String s && !t.isBlank() && !s.isBlank()) {
            builder.setParent(tracer.traceContextBuilder().traceId(t).spanId(s)
                    .sampled((Boolean) input.getOrDefault(GraphStateKeys.PIPELINE_SAMPLED, Boolean.TRUE)).build());
        }
        return builder.start().tag("langfuse.trace.name", "chat")
                .tag("langfuse.session.id", AiTelemetry.pseudonym(threadId))
                .tag("langfuse.user.id", AiTelemetry.pseudonym(Objects.toString(input.get(GraphStateKeys.USER_ID), null)));
    }

    private Map<String, Object> extract(OverAllState state) {
        Map<String, Object> out = new HashMap<>();
        out.put(ROUTE_KEY, state.value(ROUTE_KEY).orElse("unknown"));
        out.put(GraphStateKeys.OUTPUT, state.value(GraphStateKeys.OUTPUT).orElse(""));
        out.put(GraphStateKeys.ADVICE_TIERS, state.value(GraphStateKeys.ADVICE_TIERS).orElse(null));
        for (String key : List.of(GraphStateKeys.GRAPH_PATH, GraphStateKeys.TOOL_EVENTS)) {
            Object value = state.value(key).orElse(null);
            out.put(key, value instanceof List<?> l ? l.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of());
        }
        return out;
    }

    private void injectHistory(Map<String, Object> input, String threadId) {
        if (input.containsKey(GraphStateKeys.MESSAGES) || input.containsKey(GraphStateKeys.SANDBOX_ID)) return;
        // DB/history failure must not silently expose unrelated context or turn into a successful empty memory.
        List<Message> window = memory.create().get(threadId);
        List<Message> messages = new ArrayList<>(window == null ? List.of() : window);
        if (input.get(GraphStateKeys.MESSAGE) != null) messages.add(new UserMessage(input.get(GraphStateKeys.MESSAGE).toString()));
        input.put(GraphStateKeys.MESSAGES, messages);
    }

    private void persist(String key, Map<String, Object> input, Map<String, Object> output) {
        List<Message> messages = new ArrayList<>();
        if (input.get(GraphStateKeys.MESSAGE) != null) messages.add(new UserMessage(input.get(GraphStateKeys.MESSAGE).toString()));
        String text = Objects.toString(output.get(GraphStateKeys.OUTPUT), "");
        if (!text.isBlank()) messages.add(new AssistantMessage(text));
        if (!messages.isEmpty()) memory.create().add(key, messages);
    }
}
