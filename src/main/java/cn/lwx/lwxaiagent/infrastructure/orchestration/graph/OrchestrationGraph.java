package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

import cn.lwx.lwxaiagent.infrastructure.orchestration.CapabilityRouter;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.AgentLlmNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.AgentToolNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.CheckNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.GraphVisionNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.NormalChatNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.OffTopicNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.SandboxChatNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node.QuickAnswerNode;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphObservability;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 业务编排图（ADR-19）：统一聊天入口的内部状态图。
 * <pre>
 * START → classify(简单判定)
 *       ├─ 是 → quick_answer → check → END
 *       └─ 否 → route(类型路由)
 *                 ├─ sbx(带沙盘会话) → sandbox → check → END     [task 5 并入]
 *                 ├─ agent(工具意图) → agent_llm ⇄ agent_tool   → check → END  [task 6 并入]
 *                 └─ 普通 → normal → check → END
 * </pre>
 * 本轮落地 simple/normal 链路；沙盘与 agent 工具循环分支由后续任务并入。
 */
@Component
public class OrchestrationGraph {

    private final CapabilityRouter router;
    private final QuickAnswerNode quickAnswerNode;
    private final OffTopicNode offTopicNode;
    private final NormalChatNode normalChatNode;
    private final SandboxChatNode sandboxChatNode;
    private final AgentLlmNode agentLlmNode;
    private final AgentToolNode agentToolNode;
    private final GraphVisionNode graphVisionNode;
    private final CheckNode checkNode;
    private final GraphObservability observability;
    private final RedisSaver redisSaver;

    private final StateGraph stateGraph;

    /** 状态键：路由结果（写于 classify，读于条件边） */
    static final String ROUTE = "graph.route";
    static final String R_SIMPLE = "simple";
    static final String R_NORMAL = "normal";
    static final String R_SANDBOX = "sandbox";
    static final String R_AGENT = "agent";
    static final String R_VISION = "vision";
    static final String R_OFF_TOPIC = "off_topic";

    public OrchestrationGraph(CapabilityRouter router,
                              QuickAnswerNode quickAnswerNode,
                              OffTopicNode offTopicNode,
                              NormalChatNode normalChatNode,
                              SandboxChatNode sandboxChatNode,
                              AgentLlmNode agentLlmNode,
                              AgentToolNode agentToolNode,
                              GraphVisionNode graphVisionNode,
                              CheckNode checkNode,
                              GraphObservability observability,
                              RedisSaver redisSaver) throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        this.router = router;
        this.quickAnswerNode = quickAnswerNode;
        this.offTopicNode = offTopicNode;
        this.normalChatNode = normalChatNode;
        this.sandboxChatNode = sandboxChatNode;
        this.agentLlmNode = agentLlmNode;
        this.agentToolNode = agentToolNode;
        this.graphVisionNode = graphVisionNode;
        this.checkNode = checkNode;
        this.observability = observability;
        this.redisSaver = redisSaver;

        this.stateGraph = new StateGraph();
        build();
    }

    private void build() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        // —— 节点 ——
        stateGraph.addNode(GraphNodes.CLASSIFY, n(GraphNodes.CLASSIFY, this::classify));
        stateGraph.addNode(GraphNodes.QUICK_ANSWER, n(GraphNodes.QUICK_ANSWER, quickAnswerNode::apply));
        stateGraph.addNode(GraphNodes.OFF_TOPIC, n(GraphNodes.OFF_TOPIC, offTopicNode::apply));
        stateGraph.addNode(GraphNodes.NORMAL, n(GraphNodes.NORMAL, normalChatNode::apply));
        stateGraph.addNode(GraphNodes.SANDBOX, n(GraphNodes.SANDBOX, sandboxChatNode::apply));
        stateGraph.addNode(GraphNodes.AGENT_LLM, n(GraphNodes.AGENT_LLM, agentLlmNode::apply));
        stateGraph.addNode(GraphNodes.AGENT_TOOL, n(GraphNodes.AGENT_TOOL, agentToolNode::apply));
        stateGraph.addNode(GraphNodes.VISION, n(GraphNodes.VISION, graphVisionNode::apply));
        stateGraph.addNode(GraphNodes.CHECK, n(GraphNodes.CHECK, checkNode::apply));

        // —— 固定边 ——
        stateGraph.addEdge(START, GraphNodes.CLASSIFY);
        stateGraph.addEdge(GraphNodes.QUICK_ANSWER, GraphNodes.CHECK);
        stateGraph.addEdge(GraphNodes.OFF_TOPIC, GraphNodes.CHECK);
        stateGraph.addEdge(GraphNodes.NORMAL, GraphNodes.CHECK);
        stateGraph.addEdge(GraphNodes.SANDBOX, GraphNodes.CHECK);
        stateGraph.addEdge(GraphNodes.VISION, GraphNodes.CHECK);
        stateGraph.addEdge(GraphNodes.AGENT_TOOL, GraphNodes.AGENT_LLM); // 工具执行后回 LLM
        stateGraph.addEdge(GraphNodes.CHECK, END);

        // —— 条件边：classify → 沙盘 / 视觉 / 简单 / 工具 / 普通 ——
        stateGraph.addConditionalEdges(GraphNodes.CLASSIFY,
                edge(this::category),
                Map.of(R_SIMPLE, GraphNodes.QUICK_ANSWER, R_NORMAL, GraphNodes.NORMAL,
                        R_SANDBOX, GraphNodes.SANDBOX, R_AGENT, GraphNodes.AGENT_LLM,
                        R_VISION, GraphNodes.VISION, R_OFF_TOPIC, GraphNodes.OFF_TOPIC));

        // —— 条件边：agent_llm → 有工具调用? agent_tool / 无 → check ——
        stateGraph.addConditionalEdges(GraphNodes.AGENT_LLM,
                edge(agentLlmNode::hasToolCall),
                Map.of(GraphNodes.AGENT_TOOL, GraphNodes.AGENT_TOOL, GraphNodes.CHECK, GraphNodes.CHECK));
    }

    /** 分类节点动作：强制工具 > 沙盘 > 视觉(带图) > 简单 > 工具意图 > 普通 */
    private Map<String, Object> classify(OverAllState state) {
        String r;
        observability.routeHit("classify-enter");
        String r2 = classifyInner(state);
        observability.routeHit(r2);
        if (R_SIMPLE.equals(r2)) {
            observability.simpleHit();
        }
        Map<String, Object> out = new HashMap<>();
        out.put(ROUTE, r2);
        return out;
    }

    private String classifyInner(OverAllState state) {
        String r;
        String preMsg = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
        if (router.isOffTopic(preMsg)) {
            r = R_OFF_TOPIC; // 域外话题（写代码等）规则拦截 → 固定引导（2026-09-05）
        } else if (state.value(GraphStateKeys.FORCE_AGENT).map(v -> Boolean.TRUE.equals(v)).orElse(false)) {
            r = R_AGENT; // LoveManus 通道强制走工具循环
        } else if (state.value(GraphStateKeys.SANDBOX_ID).isPresent()) {
            r = R_SANDBOX;
        } else if (hasMedia(state)) {
            r = R_VISION; // 带图请求直接走视觉节点（ADR-11）
        } else {
            String message = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
            if (router.isSimpleQuestion(message)) {
                r = R_SIMPLE;
            } else if (router.needTools(message, List.of())) {
                r = R_AGENT;
            } else {
                r = R_NORMAL;
            }
        }
        return r;
    }

    private boolean hasMedia(OverAllState state) {
        Object m = state.value(GraphStateKeys.MEDIA_IDS).orElse(null);
        return m instanceof List<?> l && !l.isEmpty();
    }

    /** 条件边动作：读 ROUTE → 返回分支键 */
    private String category(OverAllState state) {
        return state.value(ROUTE).map(Object::toString).orElse(R_NORMAL);
    }

    /** 编译图（GraphRunner 持有） */
    public com.alibaba.cloud.ai.graph.CompiledGraph compile() throws com.alibaba.cloud.ai.graph.exception.GraphStateException {
        // 图 checkpoint（长任务断点底座）：RedisSaver 按 threadId 持久化图状态
        return stateGraph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(redisSaver).build())
                .build());
    }

    // ==================== 动作包装 ====================

    @FunctionalInterface
    interface NodeAction {
        Map<String, Object> apply(OverAllState state);
    }

    @FunctionalInterface
    interface EdgeAction {
        String apply(OverAllState state);
    }

    private AsyncNodeAction n(String node, NodeAction action) {
        return state -> CompletableFuture.completedFuture(
                observability.execute(node, state, action::apply));
    }

    private static AsyncEdgeAction edge(EdgeAction action) {
        return state -> CompletableFuture.completedFuture(action.apply(state));
    }
}