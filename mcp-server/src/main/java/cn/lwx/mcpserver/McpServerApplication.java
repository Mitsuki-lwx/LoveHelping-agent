package cn.lwx.mcpserver;

import cn.lwx.mcpserver.tool.BaiduImageSearchTool;
import cn.lwx.mcpserver.tool.ImageSearchTool;
import cn.lwx.mcpserver.tool.WeatherTool;
import cn.lwx.mcpserver.tool.DatePlannerTool;
import cn.lwx.mcpserver.tool.WebScrapingTool;
import cn.lwx.mcpserver.tool.WebSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * <h1>MCP Server（模型上下文协议服务端）启动类</h1>
 *
 * <p>
 * 本类是整个 MCP Server 应用程序的入口，基于 Spring Boot 框架构建。
 * MCP（Model Context Protocol，模型上下文协议）是一种标准化的 AI 通信协议，
 * 它允许 AI 模型（如 Claude、GPT 等作为 MCP Client）通过标准化的接口调用外部工具和服务。
 * </p>
 *
 * <h2>MCP 协议的核心概念</h2>
 * <ul>
 *   <li><b>MCP Client</b>：AI 模型或 AI 应用端，发起工具调用请求。例如 Claude Desktop、支持 MCP 的 IDE 插件等。</li>
 *   <li><b>MCP Server</b>：提供具体工具实现的服务端（即本应用），负责接收 MCP Client 的调用请求，执行工具逻辑并返回结果。</li>
 *   <li><b>Tool（工具）</b>：MCP Server 对外暴露的能力单元，每个工具可以完成一个具体的任务，如网络搜索、图片搜索、天气查询等。</li>
 *   <li><b>ToolCallbackProvider</b>：Spring AI 框架中用于将标注了 {@code @Tool} 注解的方法包装成可回调的工具提供者。</li>
 * </ul>
 *
 * <h2>启动流程</h2>
 * <ol>
 *   <li>Spring Boot 通过 {@link SpringApplication#run} 启动整个应用。</li>
 *   <li>Spring 容器扫描所有标注了 {@code @Component} 的工具类并实例化。</li>
 *   <li>每个工具类通过 {@link MethodToolCallbackProvider} 将其标注了 {@code @Tool} 的方法注册为可回调的工具。</li>
 *   <li>MCP Server 自动发现所有 {@link ToolCallbackProvider} Bean，并通过 MCP 协议暴露给 MCP Client。</li>
 *   <li>当 AI 模型需要调用某个工具时，MCP Client 通过 MCP 协议发起远程调用，MCP Server 执行对应方法并返回结果。</li>
 * </ol>
 *
 * <h2>当前注册的工具</h2>
 * <ul>
 *   <li><b>webScrapingTools</b> — 网页抓取工具（{@link WebScrapingTool}）：抓取指定 URL 的网页文本内容</li>
 *   <li><b>webSearchTools</b> — 网络搜索工具（{@link WebSearchTool}）：通过 Google 搜索引擎检索互联网信息</li>
 *   <li><b>baiduImageSearchTools</b> — 百度图片搜索工具（{@link BaiduImageSearchTool}）：在百度图片中搜索并返回图片 URL</li>
 *   <li><b>imageSearchTools</b> — Pexels 图片搜索工具（{@link ImageSearchTool}）：通过 Pexels API 搜索高质量图片</li>
 * </ul>
 *
 * <p>
 * <b>注意</b>：本类还依赖 {@code DatePlannerTool} 和 {@code WeatherTool}，
 * 但它们由 Spring 容器自动发现并注册（通过 {@code @Component} + {@code @Tool} 注解的组合），
 * 无需在此处显式声明 {@code @Bean} 方法。
 * </p>
 *
 * @author lwx
 * @version 1.0
 * @see ToolCallbackProvider
 * @see MethodToolCallbackProvider
 * @since 2025
 */
@SpringBootApplication
public class McpServerApplication {

    /**
     * Spring Boot 应用程序的主入口方法。
     *
     * <p>
     * 此方法启动整个 MCP Server 服务。Spring Boot 会自动完成以下工作：
     * </p>
     * <ol>
     *   <li>创建 Spring 应用上下文（ApplicationContext）</li>
     *   <li>扫描并实例化所有组件（包路径 {@code cn.lwx.mcpserver} 及其子包下的所有 Bean）</li>
     *   <li>启动嵌入式的 Web 服务器（如果有配置 MCP 的传输方式为 HTTP/SSE 等）</li>
     *   <li>注册所有工具到 MCP Server，等待 MCP Client 的连接和工具调用</li>
     * </ol>
     *
     * @param args 命令行参数，传递给 Spring Boot 应用程序，可用于覆盖配置文件中的参数
     */
    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }

    /**
     * 注册网页抓取工具（{@link WebScrapingTool}）作为 MCP 工具提供者。
     *
     * <p>
     * Spring AI 的 {@link MethodToolCallbackProvider} 会自动扫描传入对象中所有标注了
     * {@code @Tool} 注解的方法，并将每个方法包装成一个可被 MCP Client 调用的工具回调。
     * </p>
     *
     * <p>
     * 当 MCP Client（如 AI 模型）需要获取某个网页的完整内容时，会通过 MCP 协议
     * 调用此工具。调用流程如下：
     * </p>
     * <ol>
     *   <li>AI 模型分析用户问题，判断需要抓取网页内容</li>
     *   <li>AI 模型通过 MCP Client 发送工具调用请求，携带目标 URL 参数</li>
     *   <li>MCP Server 接收到请求后，路由到 {@link WebScrapingTool#scrapeWebPage(String)} 方法</li>
     *   <li>方法执行完成后，将结果通过 MCP 协议返回给 AI 模型</li>
     * </ol>
     *
     * @param webScrapingTool Spring 容器自动注入的网页抓取工具实例（由 {@code @Component} 注解创建）
     * @return 包含网页抓取工具所有 {@code @Tool} 方法的回调提供者，Spring 将其注册为 Bean 供 MCP Server 使用
     */
    @Bean
    public ToolCallbackProvider webScrapingTools(WebScrapingTool webScrapingTool) {
        return MethodToolCallbackProvider.builder().toolObjects(webScrapingTool).build();
    }

    /**
     * 注册网络搜索工具（{@link WebSearchTool}）作为 MCP 工具提供者。
     *
     * <p>
     * 此工具封装了 Google 搜索引擎的检索能力（通过 Serper API），
     * 允许 AI 模型获取实时的互联网搜索结果，弥补 AI 模型知识截止日期的限制。
     * </p>
     *
     * <p>
     * 典型的调用场景：当用户询问最新的新闻、实时信息、或 AI 训练数据中不包含的知识时，
     * AI 模型会主动调用此工具搜索互联网。
     * </p>
     *
     * @param webSearchTool Spring 容器自动注入的网络搜索工具实例
     * @return 包含网络搜索工具所有 {@code @Tool} 方法的回调提供者
     */
    @Bean
    public ToolCallbackProvider webSearchTools(WebSearchTool webSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(webSearchTool).build();
    }

    /**
     * 注册百度图片搜索工具（{@link BaiduImageSearchTool}）作为 MCP 工具提供者。
     *
     * <p>
     * 此工具通过解析百度图片搜索的 JSON API 接口来获取图片 URL，
     * 专门针对中文关键词进行了优化，支持分页获取和去重处理，最多返回 6 张不重复的图片。
     * </p>
     *
     * <p>
     * 典型的调用场景：用户需要查找某个中文关键词的图片资料（如"故宫"、"西湖"等），
     * AI 模型调用此工具获取相关的真实图片链接。
     * </p>
     *
     * @param baiduImageSearchTool Spring 容器自动注入的百度图片搜索工具实例
     * @return 包含百度图片搜索工具所有 {@code @Tool} 方法的回调提供者
     */
    @Bean
    public ToolCallbackProvider baiduImageSearchTools(BaiduImageSearchTool baiduImageSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(baiduImageSearchTool).build();
    }

    /**
     * 注册 Pexels 图片搜索工具（{@link ImageSearchTool}）作为 MCP 工具提供者。
     *
     * <p>
     * 此工具通过 Pexels 官方 API 搜索高质量的国际图片资源，
     * 相比百度图片搜索，Pexels 提供更多高质量的英文关键词图片。
     * </p>
     *
     * <p>
     * 典型的调用场景：用户需要高质量的创意图片、风景照、或国际化的图片素材，
     * AI 模型调用此工具从 Pexels 图库中检索。
     * </p>
     *
     * @param imageSearchTool Spring 容器自动注入的 Pexels 图片搜索工具实例
     * @return 包含 Pexels 图片搜索工具所有 {@code @Tool} 方法的回调提供者
     */
    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder().toolObjects(imageSearchTool).build();
    }

    /**
     * 注册天气查询工具（{@link WeatherTool}）作为 MCP 工具提供者：
     * 「今天适合约会吗」「周末天气怎么样」类问题。
     */
    @Bean
    public ToolCallbackProvider weatherTools(WeatherTool weatherTool) {
        return MethodToolCallbackProvider.builder().toolObjects(weatherTool).build();
    }

    /**
     * 注册日期/约会规划工具（{@link DatePlannerTool}）作为 MCP 工具提供者。
     */
    @Bean
    public ToolCallbackProvider datePlannerTools(DatePlannerTool datePlannerTool) {
        return MethodToolCallbackProvider.builder().toolObjects(datePlannerTool).build();
    }
}
