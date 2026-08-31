package cn.lwx.lwxaiagent.infrastructure.orchestration.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Agent 工具白名单策略单测（ADR-19 能力治理）：白名单过滤 / 未识别工具默认排除 / 可配。
 */
class AgentToolPolicyTest {

    private ToolCallback tool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition td = mock(ToolDefinition.class);
        when(td.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(td);
        return cb;
    }

    @Test
    void filter_keepsOnlyAllowlistedCapabilities() {
        AgentToolPolicy policy = new AgentToolPolicy(List.of("terminate", "retrieval"));
        ToolCallback[] out = policy.filter(new ToolCallback[]{
                tool("doTerminate"),        // terminate 域 ✓
                tool("searchKnowledge"),  // retrieval 域 ✓
                tool("generatePDF"),      // pdf 域 ✗
                tool("downloadImages")    // download 域 ✗
        });
        assertEquals(2, out.length, "只保留白名单域工具");
        assertEquals("doTerminate", out[0].getToolDefinition().name());
        assertEquals("searchKnowledge", out[1].getToolDefinition().name());
    }

    @Test
    void filter_unknownTool_defaultExcluded() {
        // 新增/未映射域的工具默认排除（安全默认：不会自动暴露给 agent）
        AgentToolPolicy policy = new AgentToolPolicy(List.of("terminate", "retrieval"));
        ToolCallback[] out = policy.filter(new ToolCallback[]{tool("brand_new_tool")});
        assertEquals(0, out.length, "未打标工具应被排除");
    }

    @Test
    void filter_nullInput_returnsEmpty() {
        AgentToolPolicy policy = new AgentToolPolicy(null);
        assertEquals(0, policy.filter(null).length);
    }

    @Test
    void filter_customAllowlist_respectsConfig() {
        AgentToolPolicy policy = new AgentToolPolicy(List.of("pdf")); // 仅放行 pdf
        ToolCallback[] out = policy.filter(new ToolCallback[]{
                tool("searchKnowledge"), tool("generatePDF")});
        assertEquals(1, out.length);
        assertEquals("generatePDF", out[0].getToolDefinition().name());
    }
}