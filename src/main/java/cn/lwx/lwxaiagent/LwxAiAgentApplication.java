package cn.lwx.lwxaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * <h1>LWX AI Agent 项目启动类</h1>
 *
 * <p>这是整个 Spring Boot 应用的入口点，负责：</p>
 * <ul>
 *   <li><b>启动 Spring 容器</b>：通过 {@link SpringApplication#run} 引导整个 IoC 容器和自动配置</li>
 *   <li><b>组件扫描</b>：{@code @SpringBootApplication} 会自动扫描当前包及子包下的所有 Bean（如 Controller、Service、Config 等）</li>
 *   <li><b>配置属性扫描</b>：{@code @ConfigurationPropertiesScan} 会自动发现并注册所有用 {@code @ConfigurationProperties} 标注的配置类（例如 {@code PgvectorProperties}、{@code EvolutionProperties}、{@code MemoryProperties} 等）</li>
 * </ul>
 *
 * <p><b>启动流程：</b></p>
 * <ol>
 *   <li>JVM 调用 main 方法</li>
 *   <li>SpringApplication.run 执行，创建 ApplicationContext（应用上下文）</li>
 *   <li>Spring Boot 根据 classpath 中引入的 starter（如 spring-boot-starter-web、spring-ai-starter 等）进行自动配置</li>
 *   <li>扫描所有 Bean 并注入依赖，完成 IoC 容器的初始化</li>
 *   <li>内嵌的 Tomcat 服务器启动，监听配置文件中指定的端口（默认 8080）</li>
 * </ol>
 *
 * @author lwx
 * @since 1.0
 */
@SpringBootApplication  // 组合注解 = @Configuration + @EnableAutoConfiguration + @ComponentScan，是 Spring Boot 的核心入口
@ConfigurationPropertiesScan  // 自动扫描所有 @ConfigurationProperties 注解的配置类，无需手动一个个注册
public class LwxAiAgentApplication {

    /**
     * 应用程序的主方法——Java 程序的入口点
     *
     * <p>注意：曾在此开启 {@code Hooks.enableAutomaticContextPropagation()}，实测会导致
     * SSE 请求的 http server span 不再导出到 Langfuse（请求级 trace 整体丢失）——已回退。
     * 流式 chat generation 仍可能与请求 trace 分离，属已知可观测性缺口（见 2026-09-01 记忆）。</p>
     *
     * @param args 命令行参数，可在启动时传入（例如 --server.port=9090 来覆盖端口配置）
     */
    public static void main(String[] args) {
        // 启动 Spring Boot 应用：
        // 1. 传入主配置类 LwxAiAgentApplication.class 作为配置源
        // 2. run 方法返回 ConfigurableApplicationContext，即 Spring 容器本身
        SpringApplication.run(LwxAiAgentApplication.class, args);
    }

}
