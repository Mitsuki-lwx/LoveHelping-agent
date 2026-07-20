package cn.lwx.mcpserver;

import cn.lwx.mcpserver.tool.BaiduImageSearchTool;
import cn.lwx.mcpserver.tool.ImageSearchTool;
import cn.lwx.mcpserver.tool.WebScrapingTool;
import cn.lwx.mcpserver.tool.WebSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider webScrapingTools(WebScrapingTool webScrapingTool) {
        return MethodToolCallbackProvider.builder().toolObjects(webScrapingTool).build();
    }

    @Bean
    public ToolCallbackProvider webSearchTools(WebSearchTool webSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(webSearchTool).build();
    }

    @Bean
    public ToolCallbackProvider baiduImageSearchTools(BaiduImageSearchTool baiduImageSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(baiduImageSearchTool).build();
    }

    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(imageSearchTool).build();
    }
}
