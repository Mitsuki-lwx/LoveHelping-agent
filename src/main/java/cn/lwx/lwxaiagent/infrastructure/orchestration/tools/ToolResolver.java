package cn.lwx.lwxaiagent.infrastructure.orchestration.tools;

import cn.lwx.lwxaiagent.tools.KnowledgeSearchTool;
import cn.lwx.lwxaiagent.tools.PDFGenerationTool;
import cn.lwx.lwxaiagent.tools.ResourceDownloadTool;
import cn.lwx.lwxaiagent.tools.TerminateTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工具运行时解析器（ADR-19 能力治理 · MCP 时序修复）。
 * <p>MCP client 在 1.1.8 为懒连接——启动时抓工具数组会拿到空 MCP 集（连上后数组已固定）。
 * 改为每次调用时实时合并 本地工具 + 当前 MCP 工具：首次 agent 执行时触发 MCP 连接并补入工具。</p>
 * <p>安全说明（Phase 0）：FileOperationTool / TerminalOperationTool 不在注册表——允许 LLM
 * 执行任意文件读写/终端命令等于开放宿主机（docs/07 §3）。</p>
 */
@Slf4j
@Component
public class ToolResolver {

    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
    private final KnowledgeSearchTool knowledgeSearchTool;

    public ToolResolver(ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                        KnowledgeSearchTool knowledgeSearchTool) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.knowledgeSearchTool = knowledgeSearchTool;
    }

    /** 实时合并 本地工具 + MCP 工具（MCP 不可用时降级仅本地） */
    public ToolCallback[] resolve() {
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminateTool terminateTool = new TerminateTool();
        List<ToolCallback> all = new ArrayList<>();
        all.addAll(Arrays.asList(ToolCallbacks.from(
                terminateTool, pdfGenerationTool, resourceDownloadTool, knowledgeSearchTool)));
        try {
            ToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
            if (provider != null) {
                ToolCallback[] mcp = provider.getToolCallbacks();
                if (mcp != null) {
                    all.addAll(Arrays.asList(mcp));
                }
            }
        } catch (Exception e) {
            log.warn("MCP 工具拉取失败，降级为仅本地工具: {}", e.getMessage());
        }
        if (log.isInfoEnabled()) {
            List<String> names = new ArrayList<>();
            for (ToolCallback cb : all) {
                names.add(cb.getToolDefinition() != null ? cb.getToolDefinition().name() : "?");
            }
            log.info("ToolResolver: {} tools -> {}", all.size(), names);
        }
        return all.toArray(new ToolCallback[0]);
    }
}