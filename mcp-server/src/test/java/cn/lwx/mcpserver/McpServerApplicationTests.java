package cn.lwx.mcpserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <h1>MCP Server 应用启动测试</h1>
 *
 * <p>验证 MCP Server 模块的 Spring 容器能否正常启动，
 * 所有 Bean（尤其是 MCP Server 自动配置和工具类）能否正确加载。</p>
 */
@SpringBootTest  // 启动完整的 Spring Boot 应用上下文（集成测试）
class McpServerApplicationTests {

    /**
     * 验证 Spring 上下文能否成功加载
     *
     * <p>如果 MCP 工具注册失败、配置有误等问题，容器启动会抛异常</p>
     */
    @Test
    void contextLoads() {
        // 空方法体——容器启动成功即通过
    }

}
