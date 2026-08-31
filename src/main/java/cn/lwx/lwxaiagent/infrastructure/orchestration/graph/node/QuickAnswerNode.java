package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.infrastructure.orchestration.CapabilitySet;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 简单问题直接回答节点（ADR-19 CAP-2）：问候/感谢/短情绪句最短路径。
 * 复用 ChatExecutor 但不激活话术三级（advice=false）。行为与普通节点等价但无三牌指令。
 */
@Component
public class QuickAnswerNode {

    private final ChatExecutor chatExecutor;

    public QuickAnswerNode(ChatExecutor chatExecutor) {
        this.chatExecutor = chatExecutor;
    }

    public Map<String, Object> apply(OverAllState state) {
        String message = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("anon");

        AgentResult.ShallowResult sr = (AgentResult.ShallowResult)
                chatExecutor.execute(message, chatId, CapabilitySet.plain(), null, false);
        String full = sr.flux().collectList().block().stream().reduce(String::concat).orElse("");

        Map<String, Object> out = new HashMap<>();
        out.put(GraphStateKeys.OUTPUT, NormalChatNode.stripAdviceMarker(full));
        return out;
    }
}