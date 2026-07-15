package cn.lwx.lwxaiagent.tools;

import jakarta.annotation.Resource;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolRegistration {
    @Value("${search-api.api-key:}")
    private String searchApiKey;

    @Resource
    private KnowledgeSearchTool knowledgeSearchTool;

    @Bean
    public ToolCallback[] allTools() {

        FileOperationTool fileOperationTool = new FileOperationTool();
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        BaiduImageSearchTool baiduImageSearchTool = new BaiduImageSearchTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminateTool terminateTool = new TerminateTool();
        return ToolCallbacks.from(
                terminateTool,
                fileOperationTool,
                webScrapingTool,
                webSearchTool,
                baiduImageSearchTool,
                terminalOperationTool,
                pdfGenerationTool,
                resourceDownloadTool,
                knowledgeSearchTool
        );

    }
}
