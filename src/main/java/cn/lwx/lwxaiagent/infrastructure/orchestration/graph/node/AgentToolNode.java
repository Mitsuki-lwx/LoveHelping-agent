package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.StreamRegistry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具循环 · 工具执行节点（ADR-19 CAP-4，自绘）。
 * 读最后一条 assistant 消息的 tool_calls，按名解析 {@link ToolCallback} 用参数调用，
 * 结果封装为 {@link ToolResponseMessage} 追加到 {@link GraphStateKeys#MESSAGES}。
 * <p>执行审计（ADR-19 能力治理）：每次工具调用记结构化日志 + metric
 * （userId/会话/工具名/成败），供事后追溯。</p>
 * 图条件边把控制流带回 {@code agent_llm}。
 */
@Slf4j
@Component
public class AgentToolNode {

    private final AgentLlmNode llmNode;
    private final MeterRegistry meterRegistry;
    private final StreamRegistry streamRegistry;

    public AgentToolNode(AgentLlmNode llmNode, MeterRegistry meterRegistry, StreamRegistry streamRegistry) {
        this.llmNode = llmNode;
        this.meterRegistry = meterRegistry;
        this.streamRegistry = streamRegistry;
    }

    public Map<String, Object> apply(OverAllState state) {
        List<Message> messages = new ArrayList<>();
        Object existing = state.value(GraphStateKeys.MESSAGES).orElse(null);
        if (existing instanceof List<?> l) {
            for (Object o : l) if (o instanceof Message m) messages.add(m);
        }
        List<String> toolEvents = new ArrayList<>();
        Object prior = state.value(GraphStateKeys.TOOL_EVENTS).orElse(null);
        if (prior instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s) toolEvents.add(s);
        }
        String userId = state.value(GraphStateKeys.USER_ID).map(Object::toString).orElse("?");
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("?");
        Map<String, Object> out = new HashMap<>();
        if (messages.isEmpty()) {
            out.put(GraphStateKeys.MESSAGES, messages);
            out.put(GraphStateKeys.TOOL_EVENTS, toolEvents);
            return out;
        }
        Message last = messages.get(messages.size() - 1);
        if (last instanceof AssistantMessage am && am.getToolCalls() != null) {
            StreamRegistry.StreamSink sink = streamRegistry.get(chatId);
            for (ToolCall tc : am.getToolCalls()) {
                ToolCallback cb = llmNode.resolveTool(tc.name());
                if (cb == null) {
                    log.warn("AgentToolNode: unknown tool '{}', skipping", tc.name());
                    continue;
                }
                toolEvents.add(tc.name()); // SSE 🔧 工具可视化（多轮循环累积，兜底）
                // 真流式（ADR-21）：🔧 事件在工具执行时实时发给用户（排在后续回答前），
                // 而非图完成才补发——避免工具卡片跑到最终回答后面
                if (sink != null) {
                    sink.append("🔧 调用工具: " + tc.name());
                    sink.markToolsStreamed();
                }
                String result;
                String outcome = "success";
                try {
                    result = cb.call(tc.arguments() == null ? "" : tc.arguments());
                } catch (Exception e) {
                    outcome = "error";
                    log.warn("AgentToolNode: tool '{}' failed: {}", tc.name(), e.getMessage());
                    result = "工具调用失败: " + e.getMessage();
                }
                // 审计：结构化日志 + metric（ADR-19 能力治理）
                String argsBrief = tc.arguments() == null ? "" : brief(tc.arguments());
                log.info("TOOL_AUDIT userId={} chatId={} tool={} outcome={} args={}",
                        userId, chatId, tc.name(), outcome, argsBrief);
                try {
                    meterRegistry.counter("tool.call", "name", tc.name(), "outcome", outcome).increment();
                } catch (Exception ignored) {}
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponse(tc.id(), tc.name(), result)))
                        .build());
            }
        }
        out.put(GraphStateKeys.MESSAGES, messages);
        out.put(GraphStateKeys.TOOL_EVENTS, toolEvents);
        return out;
    }

    /** 参数摘要（截断，避免把敏感参数原文塞日志） */
    private String brief(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}