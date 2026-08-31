package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.infrastructure.orchestration.CapabilitySet;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 普通对话节点（ADR-19 CAP-1/CAP-9）：记忆注入 + 知识库上下文 + 话术三级激活。
 * <p>复用 {@link ChatExecutor} 的既有执行与提示词，保证与旧路径行为零差异；
 * 聚合全文并将 {@code @@ADVICE@@} 标记剥离为结构化 tiers（SSE advice 事件用）。</p>
 */
@Component
public class NormalChatNode {

    private final ChatExecutor chatExecutor;

    public NormalChatNode(ChatExecutor chatExecutor) {
        this.chatExecutor = chatExecutor;
    }

    public Map<String, Object> apply(OverAllState state) {
        String message = str(state, GraphStateKeys.MESSAGE);
        String chatId = str(state, GraphStateKeys.CHAT_ID, "anon");
        boolean advice = bool(state, GraphStateKeys.ADVICE, false);

        AgentResult.ShallowResult sr = (AgentResult.ShallowResult)
                chatExecutor.executeWithRag(message, chatId, null, advice);
        String full = sr.flux().collectList().block().stream().reduce(String::concat).orElse("");

        Map<String, Object> out = new HashMap<>();
        out.put(GraphStateKeys.OUTPUT, stripAdviceMarker(full));
        String tiers = adviceTiersJson(full);
        if (tiers != null) {
            out.put(GraphStateKeys.ADVICE_TIERS, tiers);
        }
        return out;
    }

    /** 剥离 @ADVICE@ 标记，得到纯文本输出 */
    static String stripAdviceMarker(String full) {
        int idx = full.indexOf(ChatExecutor.ADVICE_EVENT_MARKER);
        return idx >= 0 ? full.substring(0, idx) : full;
    }

    /** 从全文提取话术三级结构化 JSON（无标记返回 null） */
    static String adviceTiersJson(String full) {
        int idx = full.indexOf(ChatExecutor.ADVICE_EVENT_MARKER);
        if (idx < 0) return null;
        return full.substring(idx + ChatExecutor.ADVICE_EVENT_MARKER.length());
    }

    protected String str(OverAllState state, String key) {
        return str(state, key, "");
    }

    protected String str(OverAllState state, String key, String def) {
        Optional<Object> v = state.value(key);
        return v.map(Object::toString).orElse(def);
    }

    protected boolean bool(OverAllState state, String key, boolean def) {
        Optional<Object> v = state.value(key);
        if (v.isPresent() && v.get() instanceof Boolean b) return b;
        return def;
    }
}