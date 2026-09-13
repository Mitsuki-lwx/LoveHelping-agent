package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.StreamRegistry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Audited tool execution: one response per call ID, no raw arguments/errors in logs or traces. */
@Component
public class AgentToolNode {
    private final AgentLlmNode llmNode;
    private final MeterRegistry meters;
    private final StreamRegistry registry;
    private final AiTelemetry telemetry;
    @Autowired
    public AgentToolNode(AgentLlmNode llmNode, MeterRegistry meters, StreamRegistry registry, AiTelemetry telemetry) {
        this.llmNode = llmNode; this.meters = meters; this.registry = registry; this.telemetry = telemetry;
    }
    public AgentToolNode(AgentLlmNode llmNode, MeterRegistry meters, StreamRegistry registry) {
        this(llmNode, meters, registry, new AiTelemetry(Tracer.NOOP));
    }
    public Map<String,Object> apply(OverAllState state) {
        List<Message> messages = new ArrayList<>();
        Object existing = state.value(GraphStateKeys.MESSAGES).orElse(null);
        if (existing instanceof List<?> list) for (Object item : list) if (item instanceof Message m) messages.add(m);
        List<String> events = new ArrayList<>();
        Object prior = state.value(GraphStateKeys.TOOL_EVENTS).orElse(null);
        if (prior instanceof List<?> list) for (Object item : list) if (item instanceof String s) events.add(s);
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("");
        if (!messages.isEmpty() && messages.getLast() instanceof AssistantMessage assistant) {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (var call : assistant.getToolCalls()) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Agent cancelled");
                var tool = llmNode.resolveTool(call.name());
                if (tool == null) {
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), "工具不存在或未授权，请不要重试该工具"));
                    continue;
                }
                var span = telemetry.start("agent.tool", telemetry.capture());
                span.tag("tool.name", tool.getToolDefinition().name());
                String outcome = "success";
                String result;
                try (var ignored = telemetry.scope(span)) {
                    events.add(call.name());
                    var sink = registry.get(chatId);
                    if (sink != null) { sink.append("调用工具: " + call.name()); sink.markToolsStreamed(); }
                    result = tool.call(call.arguments() == null ? "{}" : call.arguments());
                    if (result == null) result = "工具未返回结果";
                    if (result.length() > 16000) result = result.substring(0, 16000) + " [工具结果已截断]";
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted() || e instanceof CancellationException) throw new CancellationException("Agent cancelled");
                    outcome = "error";
                    telemetry.failure(span, "tool_failed");
                    result = "工具调用失败，请稍后再试或使用已有信息，不能编造工具结果";
                } finally {
                    span.tag("tool.outcome", outcome); span.end();
                    meters.counter("tool.call", "name", tool.getToolDefinition().name(), "outcome", outcome).increment();
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            if (!responses.isEmpty()) messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
        return Map.of(GraphStateKeys.MESSAGES, messages, GraphStateKeys.TOOL_EVENTS, events);
    }
}
