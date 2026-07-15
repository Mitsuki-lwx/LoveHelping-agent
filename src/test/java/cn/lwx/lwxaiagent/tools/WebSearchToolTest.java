package cn.lwx.lwxaiagent.tools;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("local")
class WebSearchToolTest {
    
    @Value("${search-api.api-key:}")
    private String apiKeyFromConfig;
    
    @Test
    void searchWebReturnsContent() throws Exception {
        String apiKey = System.getProperty("searchApiKey");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("SEARCH_API_KEY");
        }
        // 如果系统属性和环境变量都没有，尝试使用 Spring 配置
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = apiKeyFromConfig;
        }
        
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "Missing SearchAPI key");

        WebSearchTool tool = new WebSearchTool();
        setField(tool, "apiKey", apiKey);

        String result = tool.searchWeb("Animal Planet");
        assertFalse(result.isBlank(), "Empty result");
        assertFalse(result.startsWith("Error searching Baidu:"), result);
        System.out.println(result);

    }

    private static void setField(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
