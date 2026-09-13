package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.service.SandboxService;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 沙盘对话节点（ADR-19 CAP-8/CAP-9）：人设 prompt + 沙盘记忆 + 动态情绪 + RAG。
 * <p>通过 {@link SandboxService#buildSandboxPrompt} 组装沙盘人格上下文，
 * 走 {@link ChatExecutor#executeWithRag}（沙盘同频知识库检索增强）。</p>
 */
@Slf4j
@Component
public class SandboxChatNode {

    private final SandboxService sandboxService;
    private final ChatExecutor chatExecutor;

    public SandboxChatNode(SandboxService sandboxService, ChatExecutor chatExecutor) {
        this.sandboxService = sandboxService;
        this.chatExecutor = chatExecutor;
    }

    public Map<String, Object> apply(OverAllState state) {
        String message = state.value(GraphStateKeys.MESSAGE).map(Object::toString).orElse("");
        String chatId = state.value(GraphStateKeys.CHAT_ID).map(Object::toString).orElse("anon");
        String userId = state.value(GraphStateKeys.USER_ID).map(Object::toString).orElse("anonymous");
        long sandboxId = state.value(GraphStateKeys.SANDBOX_ID)
                .map(v -> ((Number) v).longValue()).orElse(-1L);

        if (sandboxId <= 0) {
            log.warn("SandboxChatNode without valid sandboxId, fallback to plain: {}", sandboxId);
        }
        String sandboxPrompt = sandboxService.buildSandboxPrompt(sandboxId, userId);

        AgentResult.ShallowResult sr = chatExecutor.executeWithRag(message, chatId, sandboxPrompt, false);
        String full = sr.flux().reduce(new StringBuilder(), StringBuilder::append)
                .map(StringBuilder::toString).block();
        // Failures must propagate so pipeline traces and task state cannot report false success.
        return Map.of(GraphStateKeys.OUTPUT, full == null ? "" : full);
    }
}