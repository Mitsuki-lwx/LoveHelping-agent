package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.StreamRegistry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphNodes;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.infrastructure.orchestration.tools.AgentToolPolicy;
import cn.lwx.lwxaiagent.infrastructure.orchestration.tools.ToolResolver;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具循环 · LLM 生成节点（ADR-19 CAP-4，自绘）。
 * 读 {@link GraphStateKeys#MESSAGES}，调用主模型（LlmGateway）生成 assistant 消息
 * （可能带 tool_calls，spring-ai 不开内部执行）；无 tool_calls 时把文本写入 OUTPUT。
 * <p>2026-09-03 真流式（ADR-21）：`chatModel.stream` + 文本增量经 {@link StreamRegistry}
 * 实时转发 SSE——中间轮"边想边说"的说明文本、最终轮回复都能打字机呈现；
 * 聚合用 {@link MessageAggregator}（tool_calls 分片合并），工具判定逻辑不变。</p>
 * <p>工具集每次图执行前实时解析（{@link ToolResolver}）+ 白名单过滤（{@link AgentToolPolicy}）：
 * MCP 懒连接下首次 agent 请求时工具才补入；未在白名单域的工具一律不可见。</p>
 */
@Slf4j
@Component
public class AgentLlmNode {

    private static final int MAX_STEPS = 15;

    private final ChatModel chatModel;
    private final ToolResolver toolResolver;
    private final AgentToolPolicy toolPolicy;
    private final StreamRegistry streamRegistry;

    private transient ToolCallback[] currentTools = new ToolCallback[0];

    public AgentLlmNode(ChatModel chatModel, ToolResolver toolResolver, AgentToolPolicy toolPolicy,
                        StreamRegistry streamRegistry) {
        this.chatModel = chatModel;
        this.toolResolver = toolResolver;
        this.toolPolicy = toolPolicy;
        this.streamRegistry = streamRegistry;
    }

    public Map<String, Object> apply(OverAllState state) {
        // 实时解析 + 白名单过滤（MCP 懒连接：首次执行时工具才补入）
        this.currentTools = toolPolicy.filter(toolResolver.resolve());
        List<Message> messages = new ArrayList<>();
        Object existing = state.value(GraphStateKeys.MESSAGES).orElse(null);
        if (existing instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Message m) messages.add(m);
            }
        } else {
            // 首次：用用户消息建立初始消息
            String msg = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
            messages.add(new UserMessage(msg));
        }

        // 2026-09-03 评估后回退：MessageAggregator 聚合在 LlmGateway 流上不稳（实测可致
        // 空聚合→空流），agent 多轮 tool_calls 语义要求完整响应——保持 call()（9/2 已验证），
        // 工具事件实时化由 AgentToolNode 承担（🔧 提前到文本前）。normal/simple 主链路已真流式。
        ChatResponse resp = chatModel.call(new Prompt(messages, ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(currentTools))
                .internalToolExecutionEnabled(false)
                .build()));

        Map<String, Object> out = new HashMap<>();
        if (resp.getResult() != null && resp.getResult().getOutput() instanceof AssistantMessage am) {
            messages.add(am);
            boolean hasTools = am.getToolCalls() != null && !am.getToolCalls().isEmpty();
            if (!hasTools && am.getText() != null) {
                out.put(GraphStateKeys.OUTPUT, am.getText()); // 无工具 = 最终回复
            }
            out.put(GraphStateKeys.MESSAGES, messages);
        } else {
            log.warn("AgentLlmNode: model returned non-assistant output, ending cycle");
            out.put(GraphStateKeys.MESSAGES, messages);
        }
        return out;
    }

    /** 有工具调用则回工具节点，否则结束循环进检查 */
    public String hasToolCall(OverAllState state) {
        Object existing = state.value(GraphStateKeys.MESSAGES).orElse(null);
        if (existing instanceof List<?> l && !l.isEmpty()) {
            Object last = l.get(l.size() - 1);
            if (last instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                return GraphNodes.AGENT_TOOL;
            }
        }
        return GraphNodes.CHECK;
    }

    static String systemPrompt() {
        return ChatExecutor.SYSTEM_PROMPT + "\n\n" + NEXT_STEP;
    }

    private static final String NEXT_STEP = """
            You have tools available — use them as needed to complete the task.
            Break complex requests into steps. When done, give the final answer.""";

    /** 工具名 → 匹配的 ToolCallback */
    public ToolCallback resolveTool(String name) {
        if (name == null || currentTools == null) return null;
        for (ToolCallback cb : currentTools) {
            if (name.equals(cb.getToolDefinition().name())) return cb;
        }
        return null;
    }

    int maxSteps() {
        return MAX_STEPS;
    }
}