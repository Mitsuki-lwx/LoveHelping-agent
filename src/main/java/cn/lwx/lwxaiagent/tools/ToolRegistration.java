package cn.lwx.lwxaiagent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <h2>工具注册配置类</h2>
 * <p>
 * 作为 Spring 的 {@link Configuration @Configuration} 类，负责将所有本地工具类和通过
 * MCP（Model Context Protocol）协议引入的外部工具统一注册为 Spring Bean，
 * 供 {@link cn.lwx.lwxaiagent.infrastructure.ai.LoveApp} 的 ChatClient 使用。
 * </p>
 *
 * <h3>核心职责</h3>
 * <p>
 * 本类是整个工具系统（Tools）的<b>中央注册枢纽</b>，完成以下工作：
 * </p>
 * <ol>
 *   <li><b>实例化本地工具</b>：手动创建所有本地工具类的实例（这些类不标注 {@code @Component}，
 *       因此需要手动实例化而非依赖注入）</li>
 *   <li><b>包装为 ToolCallback</b>：通过 {@link ToolCallbacks#from(Object...)} 方法，
 *       利用反射扫描每个工具对象上的 {@link org.springframework.ai.tool.annotation.Tool @Tool} 注解，
 *       将其方法转换为 Spring AI 可以识别的 {@link ToolCallback} 对象</li>
 *   <li><b>合并外部工具</b>：从 {@link ToolCallbackProvider#getToolCallbacks()} 获取
 *       通过 MCP 协议动态注册的外部工具回调</li>
 *   <li><b>输出统一数组</b>：将本地工具和 MCP 工具合并为一个 {@link ToolCallback} 数组，
 *       作为 Spring Bean 暴露给 ChatClient</li>
 * </ol>
 *
 * <h3>注册的工具清单</h3>
 * <table>
 *   <tr><th>工具类</th><th>功能</th><th>注册方式</th></tr>
 *   <tr><td>{@link TerminateTool}</td><td>终止当前任务</td><td>手动 new 实例化</td></tr>
 *   <tr><td>{@link PDFGenerationTool}</td><td>生成 PDF 文件</td><td>手动 new 实例化</td></tr>
 *   <tr><td>{@link ResourceDownloadTool}</td><td>下载网络资源</td><td>手动 new 实例化</td></tr>
 *   <tr><td>{@link KnowledgeSearchTool}</td><td>搜索知识库</td><td>参数注入（@Component Bean）</td></tr>
 * </table>
 *
 * <h3>已移除的工具（Phase 0 安全止血）</h3>
 * <p>
 * {@link FileOperationTool} 与 {@link TerminalOperationTool} 不再注册：
 * 在多租户部署中它们允许 LLM 操作宿主文件系统/执行命令，属于高危面。
 * 工具类保留但处于未注册状态，待沙箱化方案（docs/07 §3-4）落地后再评估恢复。
 * </p>
 *
 * <h3>为什么 KnowledgeSearchTool 由 Spring 注入而其他手动 new</h3>
 * <p>
 * {@link KnowledgeSearchTool} 使用了 {@code @Component} 注解并依赖了 Spring 管理的其他 Bean
 *（如 rag/ 检索内核、MCP 工具等），
 * 必须通过 Spring 依赖注入来管理其生命周期。
 * 而其他工具类是无状态的简单工具，无需依赖注入，手动实例化即可。
 * </p>
 *
 * <h3>MCP 协议扩展</h3>
 * <p>
 * 除本地工具外，还支持通过 MCP（Model Context Protocol）动态注册外部工具。
 * {@code mcpToolCallbackProvider} 参数由 Spring AI 的自动配置提供，
 * 包含了所有通过 MCP 服务器连接的外部工具。这种设计使得工具集可以<b>动态扩展</b>，
 * 无需修改代码即可接入新的外部工具服务。
 * </p>
 *
 * @author lwx-ai-agent
 * @see cn.lwx.lwxaiagent.infrastructure.ai.LoveApp
 * @see ToolCallback
 * @see ToolCallbackProvider
 */
@Slf4j
@Configuration
public class ToolRegistration {

    /**
     * <h3>创建并注册所有工具回调</h3>
     * <p>
     * 这是整个工具系统的<b>核心入口方法</b>。它创建并返回一个包含所有本地工具和 MCP 外部工具的
     * {@link ToolCallback} 数组，供 ChatClient 在聊天时使用。
     * </p>
     *
     * <h4>方法流程</h4>
     * <ol>
     *   <li><b>实例化本地工具</b>：手动 {@code new} 出 5 个无状态工具类的实例</li>
     *   <li><b>扫描 @Tool 注解</b>：调用 {@link ToolCallbacks#from(Object...)} 对每个工具对象
     *       进行反射扫描，将标注了 {@link org.springframework.ai.tool.annotation.Tool @Tool} 的方法
     *       转换为 {@link ToolCallback} 对象。该过程会解析方法的参数注解
     *       {@link org.springframework.ai.tool.annotation.ToolParam @ToolParam}，
     *       提取参数名、类型和描述信息，构建完整的工具定义</li>
     *   <li><b>获取 MCP 工具</b>：从 {@code mcpToolCallbackProvider} 获取通过 MCP 协议
     *       动态注册的外部工具回调</li>
     *   <li><b>合并返回</b>：将本地工具和 MCP 工具合并为一个数组返回</li>
     * </ol>
     *
     * <h4>Bean 声明</h4>
     * <p>
     * 使用 {@code @Bean} 注解将返回的 {@link ToolCallback} 数组注册为 Spring 容器中的 Bean，
     * Bean 名称默认为方法名 {@code allTools}。
     * {@link cn.lwx.lwxaiagent.infrastructure.ai.LoveApp} 通过
     * {@code @Resource} 注解按名称注入该 Bean。
     * </p>
     *
     * <h4>ToolCallbacks.from 的工作原理</h4>
     * <p>
     * Spring AI 的 {@link ToolCallbacks#from(Object...)} 方法使用 Java 反射 API：
     * </p>
     * <ul>
     *   <li>遍历每个传入对象的所有公开方法</li>
     *   <li>查找标注了 {@link org.springframework.ai.tool.annotation.Tool @Tool} 注解的方法</li>
     *   <li>解析 {@code @Tool} 注解中的 name 和 description 属性，作为工具的名称和功能描述</li>
     *   <li>解析每个参数的 {@link org.springframework.ai.tool.annotation.ToolParam @ToolParam}
     *       注解中的 description 和 required 属性</li>
     *   <li>为每个工具方法创建一个 {@link ToolCallback} 实现，封装方法调用逻辑</li>
     *   <li>返回所有创建的 ToolCallback 对象数组</li>
     * </ul>
     *
     * @param mcpToolCallbackProvider Spring AI 自动配置提供的 MCP 工具回调提供者，
     *                                包含了所有通过 MCP 服务器注册的外部工具。
     *                                如果未配置任何 MCP 服务器，该参数可能返回空数组
     * @param knowledgeSearchTool     通过 Spring 依赖注入的知识库搜索工具实例，
     *                                这是一个 {@code @Component} Bean
     * @return {@link ToolCallback} 数组，包含所有本地工具 + MCP 外部工具。
     *         该数组会被注入到
     *         {@link cn.lwx.lwxaiagent.infrastructure.ai.LoveApp#allTools} 字段中
     */
    @Bean
    public ToolCallback[] allTools(ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                                   KnowledgeSearchTool knowledgeSearchTool) {
        // 手动实例化不需要依赖注入的简单工具类。
        // 安全说明（Phase 0）：FileOperationTool / TerminalOperationTool 已从注册中移除——
        // 多租户服务中允许 LLM 执行任意文件读写/终端命令等于开放宿主机，详见 docs/07 §3。
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminateTool terminateTool = new TerminateTool();

        // 通过 ToolCallbacks.from 扫描 @Tool 注解，将工具方法转换为 ToolCallback 数组
        ToolCallback[] localCallbacks = ToolCallbacks.from(
                terminateTool,
                pdfGenerationTool,
                resourceDownloadTool,
                knowledgeSearchTool
        );

        // MCP 工具可选注入：MCP 不可用（子进程失败/未配置）时降级为仅本地工具启动，
        // 不让工具面故障演变为整个应用无法启动。
        ToolCallback[] mcpCallbacks = new ToolCallback[0];
        try {
            ToolCallbackProvider provider = mcpToolCallbackProvider.getIfAvailable();
            if (provider != null) {
                mcpCallbacks = provider.getToolCallbacks();
            } else {
                log.warn("MCP ToolCallbackProvider 未注册，本次启动仅提供本地工具");
            }
        } catch (Exception e) {
            log.warn("MCP 工具初始化失败，降级为仅本地工具: {}", e.getMessage());
        }

        // 合并本地工具和 MCP 外部工具为一个完整列表
        List<ToolCallback> all = new ArrayList<>();
        all.addAll(Arrays.asList(localCallbacks));
        all.addAll(Arrays.asList(mcpCallbacks));
        return all.toArray(new ToolCallback[0]);
    }
}
