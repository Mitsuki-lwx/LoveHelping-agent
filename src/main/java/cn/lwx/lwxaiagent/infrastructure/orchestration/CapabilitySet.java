package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 能力叠加层：路由器判断当前消息需要哪些增强能力。
 *
 * <p>普通聊天 = CapabilitySet 全 false/empty。
 * 增强聊天 = 按需叠加（RAG + 工具 + 未来情绪感知等）。
 * 记忆（Memory）始终开启（恋爱顾问核心价值），不在此控制。</p>
 *
 * <p>路由器只返回布尔标志；执行器（{@link ChatExecutor}）根据标志
 * 按需构建 RAG Advisor、注入工具。</p>
 *
 * @param rag       知识库检索增强（ChatExecutor 按需构建 RAG Advisor）
 * @param tools     工具调用（空列表=不启用；非空=启用）
 * @param vision    多模态视觉（mediaIds 非空时由路由器置 true）
 * @param emotion   情绪感知层（未来占位）
 * @param proactive 主动追问（未来占位）
 */
public record CapabilitySet(
        boolean rag,
        List<ToolCallback> tools,
        boolean vision,
        boolean emotion,
        boolean proactive
) {

    /** 静态工厂：普通聊天（全关） */
    public static CapabilitySet plain() {
        return new CapabilitySet(false, List.of(), false, false, false);
    }

    /** 是否为增强模式（任一能力开启） */
    public boolean isEnhanced() {
        return rag || !tools.isEmpty() || emotion || proactive;
    }
}
