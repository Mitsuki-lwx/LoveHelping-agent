package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.StreamRegistry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphNodes;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.infrastructure.orchestration.tools.AgentToolPolicy;
import cn.lwx.lwxaiagent.infrastructure.orchestration.tools.ToolResolver;
import com.alibaba.cloud.ai.graph.OverAllState;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 工具循环状态机单测（ADR-19 CAP-4，Task 10）：节点级验证 LLM↔工具回填与条件边判定。
 * 不构建完整图：直接驱动 AgentLlmNode/AgentToolNode 与 hasToolCall 判定，mock 模型与工具。
 */
class GraphToolLoopTest {

    private ToolCallback kbTool() {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition td = mock(ToolDefinition.class);
        when(td.name()).thenReturn("searchKnowledge");
        when(cb.getToolDefinition()).thenReturn(td);
        when(cb.call(anyString())).thenReturn("知识库结果：非暴力沟通四要素是观察/感受/需要/请求。");
        return cb;
    }

    private AssistantMessage assistant(String text, List<ToolCall> toolCalls) {
        return AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
    }

    private ChatResponse response(AssistantMessage am) {
        return new ChatResponse(List.of(new Generation(am)));
    }

    private AgentLlmNode llmNode(ChatModel model, ToolCallback tool) {
        AgentToolPolicy policy = new AgentToolPolicy(List.of("terminate", "retrieval"));
        ToolResolver resolver = mock(ToolResolver.class);
        when(resolver.resolve()).thenReturn(new ToolCallback[]{tool});
        return new AgentLlmNode(model, resolver, policy, new StreamRegistry("discard"));
    }

    private OverAllState runLlm(AgentLlmNode node, ChatModel model, String userMsg) {
        OverAllState state = new OverAllState(Map.of(
                GraphStateKeys.MESSAGE, userMsg));
        state.updateState(node.apply(state));
        return state;
    }

    /** 真实图中由框架把节点返回的更新合并进 state；单测手动模拟这一步 */
    private void merge(OverAllState state, Map<String, Object> update) {
        state.updateState(update);
    }

    @Test
    void llm_withToolCall_routesToToolNode() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(assistant(
                "让我检索一下", List.of(new ToolCall("c1", "function", "searchKnowledge", "{\"query\":\"非暴力沟通\"}")))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(assistant(
                "让我检索一下", List.of(new ToolCall("c1", "function", "searchKnowledge", "{\"query\":\"非暴力沟通\"}"))))));
        AgentLlmNode node = llmNode(model, kbTool());

        OverAllState state = runLlm(node, model, "搜索知识库非暴力沟通内容");
        assertEquals(GraphNodes.AGENT_TOOL, node.hasToolCall(state), "带工具调用应回工具节点");
        Object messages = state.value(GraphStateKeys.MESSAGES).orElse(null);
        assertTrue(messages instanceof List<?> l && !l.isEmpty(), "应有消息被追加");
    }

    @Test
    void llm_finalNoTool_setsOutputAndEnds() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(assistant(
                "根据知识库，非暴力沟通四要素是观察、感受、需要、请求。", List.of())));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(assistant(
                "根据知识库，非暴力沟通四要素是观察、感受、需要、请求。", List.of()))));
        AgentLlmNode node = llmNode(model, kbTool());

        OverAllState state = runLlm(node, model, "搜索知识库非暴力沟通内容");
        assertEquals(GraphNodes.CHECK, node.hasToolCall(state), "无工具调用应结束循环进检查");
        String output = state.value(GraphStateKeys.OUTPUT).map(Object::toString).orElse("");
        assertTrue(output.contains("四要素"), "最终回复应写入 OUTPUT");
    }

    @Test
    void toolNode_executesToolAndFeedsBackResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(assistant(
                "让我检索一下", List.of(new ToolCall("c1", "function", "searchKnowledge", "{\"query\":\"非暴力沟通\"}")))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(assistant(
                "让我检索一下", List.of(new ToolCall("c1", "function", "searchKnowledge", "{\"query\":\"非暴力沟通\"}"))))));
        AgentLlmNode node = llmNode(model, kbTool());

        OverAllState state = runLlm(node, model, "搜索知识库非暴力沟通内容");
        AgentToolNode toolNode = new AgentToolNode(node, mock(MeterRegistry.class), new StreamRegistry("discard"));
        merge(state, toolNode.apply(state));

        Object messages = state.value(GraphStateKeys.MESSAGES).orElse(null);
        assertTrue(messages instanceof List<?> l && !l.isEmpty(), "工具执行后应有回填消息");
        @SuppressWarnings("unchecked")
        List<Message> msgs = (List<Message>) messages;
        Message last = msgs.get(msgs.size() - 1);
        assertTrue(last instanceof ToolResponseMessage, "回填消息应为 ToolResponseMessage");
        String data = ((ToolResponseMessage) last).getResponses().get(0).responseData();
        assertTrue(data.contains("四要素"), "工具结果应包含检索内容");
        // 走完一轮 LLM→工具后,再回到 LLM(无工具)→ check
        when(model.call(any(Prompt.class))).thenReturn(response(assistant(
                "根据知识库：观察/感受/需要/请求。", List.of())));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(assistant(
                "根据知识库：观察/感受/需要/请求。", List.of()))));
        merge(state, node.apply(state));
        assertEquals(GraphNodes.CHECK, node.hasToolCall(state), "第二轮无工具应结束");
    }

    @Test
    void resolveTool_byName_matches() {
        ToolCallback tool = kbTool();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(assistant("ok", List.of())));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(assistant("ok", List.of()))));
        AgentLlmNode node = llmNode(model, tool);
        OverAllState st = runLlm(node, model, "x"); // apply 触发 currentTools 解析（含白名单过滤）
        assertEquals(tool, node.resolveTool("searchKnowledge"));
        assertNull(node.resolveTool("unknown_tool"));
    }
}