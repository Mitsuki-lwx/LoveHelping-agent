package cn.lwx.lwxaiagent.infrastructure.orchestration.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent 节点工具白名单策略（ADR-19 能力治理）。
 * <p>按能力域过滤工具：只有映射了能力域、且域在 {@code app.orchestration.tools.agent-allowlist}
 * 白名单内的工具才会注入 agent 节点。未映射域的新工具默认排除（安全默认）。</p>
 * <p>默认白名单：terminate（收尾）+ retrieval（知识库检索）——排除 pdf/download 等有副作用面的工具。</p>
 */
@Slf4j
@Component
public class AgentToolPolicy {

    /** 工具名 → 能力域；新增工具须在此显式打标才会被允许进入白名单（以 ToolDefinition.name 为准） */
    private static final Map<String, String> CAPABILITY_BY_TOOL = Map.ofEntries(
            Map.entry("doTerminate", ToolCapability.TERMINATE),
            Map.entry("searchKnowledge", ToolCapability.RETRIEVAL),
            Map.entry("generatePDF", ToolCapability.PDF),
            Map.entry("downloadResource", ToolCapability.DOWNLOAD),
            Map.entry("downloadImages", ToolCapability.DOWNLOAD),
            // MCP 工具（mcp-server 8125）：只读检索/抓取/天气/日期面
            Map.entry("searchWeb", ToolCapability.WEB),
            Map.entry("scrapeWebPage", ToolCapability.WEB),
            Map.entry("searchImage", ToolCapability.IMAGE),
            Map.entry("searchBaiduImages", ToolCapability.IMAGE),
            Map.entry("getWeather", ToolCapability.WEATHER),
            Map.entry("planDate", ToolCapability.DATE)
    );

    private final List<String> allowlist;

    public AgentToolPolicy(@Value("${app.orchestration.tools.agent-allowlist:terminate,retrieval,web,image,weather,date}")
                           List<String> allowlist) {
        this.allowlist = allowlist == null ? List.of(ToolCapability.TERMINATE, ToolCapability.RETRIEVAL) : allowlist;
    }

    /** 过滤：返回白名单域内的工具；未映射域的（未知/新工具）一律排除 */
    public ToolCallback[] filter(ToolCallback[] all) {
        if (all == null) return new ToolCallback[0];
        return Arrays.stream(all)
                .filter(cb -> {
                    String name = cb.getToolDefinition() == null ? "" : cb.getToolDefinition().name();
                    String capability = CAPABILITY_BY_TOOL.get(name);
                    return capability != null && allowlist.contains(capability);
                })
                .toArray(ToolCallback[]::new);
    }

    public List<String> getAllowlist() {
        return allowlist;
    }
}