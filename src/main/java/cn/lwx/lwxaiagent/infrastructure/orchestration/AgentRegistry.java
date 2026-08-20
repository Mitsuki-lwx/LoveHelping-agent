package cn.lwx.lwxaiagent.infrastructure.orchestration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心：持有所有 AgentDefinition 实例。
 * 当前只注册一个"通用 Agent"（general），未来诊断/沙盘等新功能
 * 只需新增 AgentDefinition 条目并 register()。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final ConcurrentHashMap<String, AgentDefinition> registry = new ConcurrentHashMap<>();
    private final ToolCallback[] allTools;
    private final String generalSystemPrompt;

    public AgentRegistry(ToolCallback[] allTools,
                         @org.springframework.beans.factory.annotation.Value("${app.memory.agent-window-size:50}") int agentWindowSize) {
        this.allTools = allTools;
        this.generalSystemPrompt = ChatExecutor.SYSTEM_PROMPT;
    }

    @PostConstruct
    void init() {
        register(AgentDefinition.general(generalSystemPrompt, java.util.List.of(allTools)));
        log.info("AgentRegistry: registered 'general' agent ({} tools)", allTools.length);
    }

    public void register(AgentDefinition def) {
        registry.put(def.name(), def);
    }

    public AgentDefinition get(String name) {
        return registry.get(name);
    }

    public AgentDefinition getDefault() {
        return registry.get("general");
    }
}