package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 定义：一个"功能"的完整配置描述。
 * 可扩展——未来诊断/沙盘等新功能只需新增 AgentDefinition 条目。
 *
 * @param name             Agent 名称（"general" / "diagnosis" / "sandbox"）
 * @param systemPrompt     Agent 专属系统提示词
 * @param tools            工具集（空列表=无工具）
 * @param memoryWindowSize 记忆窗口大小（普通 20 / Agent 50）
 * @param useCheckpoint    是否启用 RedisSaver checkpoint
 * @param maxSteps         最大执行步数（默认 15）
 * @param hooks            Hook 列表（未来扩展：拦截器、安全、可观测）
 */
public record AgentDefinition(
        String name,
        String systemPrompt,
        List<ToolCallback> tools,
        int memoryWindowSize,
        boolean useCheckpoint,
        int maxSteps,
        List<?> hooks
) {
    public static AgentDefinition general(String systemPrompt, List<ToolCallback> tools) {
        return new AgentDefinition("general", systemPrompt, tools, 50, true, 15, List.of());
    }
}