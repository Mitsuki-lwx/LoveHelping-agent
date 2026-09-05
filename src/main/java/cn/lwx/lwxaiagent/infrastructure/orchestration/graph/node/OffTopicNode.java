package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.StreamRegistry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 域外话题拒绝节点（2026-09-05，agent_eval off 用例驱动）：明显与恋爱/关系无关的请求
 * （写代码/作业/办公文档等）由 {@code CapabilityRouter.isOffTopic} 规则拦到本节点——
 * 不调用 LLM，直接输出固定引导话术并结束。glm-flash 对 prompt 级拒绝遵循弱，
 * 规则层确定性兜底（Cost：规则命中即拒，不产生 LLM 成本）。
 */
@Component
public class OffTopicNode {

    /** 域外请求固定引导话术（不随用户语言变化，保持产品口吻） */
    static final String OFF_TOPIC_REPLY =
            "这个问题超出了我的专长范围哦 💕 我主要擅长恋爱和关系经营——" +
            "沟通、冲突、依恋、婚姻法律常识这些都可以问我。有什么感情上的困扰想聊聊吗？";

    private final StreamRegistry streamRegistry;

    public OffTopicNode(StreamRegistry streamRegistry) {
        this.streamRegistry = streamRegistry;
    }

    public Map<String, Object> apply(OverAllState state) {
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("anon");
        StreamRegistry.StreamSink sink = streamRegistry.get(chatId);
        if (sink != null) {
            sink.append(OFF_TOPIC_REPLY);
            sink.flush();
        }
        Map<String, Object> out = new HashMap<>();
        out.put(GraphStateKeys.OUTPUT, OFF_TOPIC_REPLY);
        return out;
    }
}
