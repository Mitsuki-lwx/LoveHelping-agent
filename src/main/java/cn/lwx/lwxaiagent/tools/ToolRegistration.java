package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class ToolRegistration {

    @Bean
    public ToolCallback[] allTools(ToolCallbackProvider mcpToolCallbackProvider, KnowledgeSearchTool knowledgeSearchTool) {
        FileOperationTool fileOperationTool = new FileOperationTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminateTool terminateTool = new TerminateTool();

        ToolCallback[] localCallbacks = ToolCallbacks.from(
                terminateTool,
                fileOperationTool,
                terminalOperationTool,
                pdfGenerationTool,
                resourceDownloadTool,
                knowledgeSearchTool
        );

        List<ToolCallback> all = new ArrayList<>();
        all.addAll(Arrays.asList(localCallbacks));
        all.addAll(Arrays.asList(mcpToolCallbackProvider.getToolCallbacks()));
        return all.toArray(new ToolCallback[0]);
    }
}
