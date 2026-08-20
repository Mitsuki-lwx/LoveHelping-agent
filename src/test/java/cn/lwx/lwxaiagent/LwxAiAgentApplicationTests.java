package cn.lwx.lwxaiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * <h1>Spring Boot 应用启动测试</h1>
 *
 * <p>验证 Spring 容器能否正常启动（所有 Bean 能否正确加载、依赖注入是否完整）。</p>
 *
 * <p>{@code @SpringBootTest} 会启动完整的 Spring 上下文，加载所有配置和 Bean。
 * 这是最基础的集成测试——如果这个测试都通不过，说明项目的配置或依赖有严重问题。</p>
 *
 * <p><b>注意：</b>此测试会真实连接配置文件中指定的数据库和外部服务。</p>
 */
@SpringBootTest  // 启动完整的 Spring Boot 应用上下文（集成测试）
class LwxAiAgentApplicationTests {

    /**
     * 验证 Spring 上下文能否成功加载
     *
     * <p>如果 Bean 创建失败、配置文件有误、循环依赖等问题，
     * Spring 容器启动会抛异常，此测试即失败。</p>
     */
    @Test
    void contextLoads() {
        // 空的测试方法体——只要 Spring 容器启动成功就通过
        // 如果启动失败，JUnit 会在 @Test 方法执行前就抛出异常
    }

}
