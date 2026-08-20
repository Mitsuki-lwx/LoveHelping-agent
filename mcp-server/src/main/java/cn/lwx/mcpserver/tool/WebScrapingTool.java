package cn.lwx.mcpserver.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * <h1>网页抓取工具</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 用于抓取指定 URL 的网页内容，提取纯文本并截断返回。
 * 标注了 {@code @Component} 注解，由 Spring 容器管理。
 * </p>
 *
 * <h2>网页抓取的作用</h2>
 * <p>
 * 在 AI 对话场景中，AI 模型通常使用搜索工具（如 {@link WebSearchTool}）获取相关信息摘要，
 * 但摘要信息有限。当需要深入了解某个网页的完整内容时，AI 会调用本工具抓取该网页的文本。
 * 典型的调用链：
 * </p>
 * <ol>
 *   <li>AI 模型调用 {@code WebSearchTool#searchWeb} 搜索关键词，获得搜索结果列表</li>
 *   <li>AI 模型从搜索结果中挑选最相关的 URL</li>
 *   <li>AI 模型调用本工具的 {@code scrapeWebPage(url)} 获取该 URL 的完整文本内容</li>
 *   <li>AI 模型基于完整的网页内容来回答用户的深度问题</li>
 * </ol>
 *
 * <h2>Jsoup 库简介</h2>
 * <p>
 * 本工具使用 Jsoup 库进行 HTML 解析。Jsoup 是一个开源的 Java HTML 解析器，
 * 提供非常便捷的 API 来处理 HTML 文档：
 * </p>
 * <ul>
 *   <li>{@link Jsoup#connect(String)}：建立到目标 URL 的 HTTP 连接</li>
 *   <li>{@link Document#text()}：提取 HTML 文档中所有可见文本（自动去除 HTML 标签、脚本、样式等）</li>
 * </ul>
 * <p>
 * Jsoup 会自动处理字符编码、HTML 实体转义等复杂问题，使网页抓取变得简单可靠。
 * </p>
 *
 * <h2>内容截断策略</h2>
 * <p>
 * 为避免返回过长的文本（可能超出 AI 模型的上下文窗口或导致 token 消耗过多），
 * 本工具设定了最大返回字符数限制 {@link #MAX_CONTENT_CHARS} = 4000 字符。
 * 超过限制的文本会被截断，末尾添加 "..." 标记。
 * </p>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @see WebSearchTool
 * @since 2025
 */
@Component
public class WebScrapingTool {

    /**
     * 网页内容的最大返回字符数。
     * 设定为 4000 字符，在提供足够上下文的同时控制 token 消耗。
     * 对于大多数网页文章，4000 字符可以覆盖开头的重要内容和核心段落。
     * 超过此限制的内容会被截断并添加 "..." 后缀。
     */
    private static final int MAX_CONTENT_CHARS = 4000;

    /**
     * <h3>抓取网页文本内容（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具，方法名被显式指定为 {@code "scrapeWebPage"}。
     * 当 AI 模型需要获取某个网页的完整文本内容时（通常在调用 {@code WebSearchTool} 之后），
     * 会通过 MCP 协议调用此方法。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>接收 AI 模型传入的目标网页 URL</li>
     *   <li>使用 Jsoup 连接到目标 URL，执行 HTTP GET 请求并解析 HTML</li>
     *   <li>调用 {@link Document#text()} 提取 HTML 中所有可见的纯文本内容</li>
     *   <li>调用 {@link #truncate(String, int)} 对文本进行截断，超过 4000 字符的部分被裁掉</li>
     *   <li>返回截断后的纯文本字符串</li>
     * </ol>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中询问某个网页的详细内容时：
     * </p>
     * <ol>
     *   <li>AI 模型先调用 {@code searchWeb("Spring AI MCP")} 获取搜索结果</li>
     *   <li>从搜索结果中找到最相关的 URL，例如 {@code "https://docs.spring.io/spring-ai/reference/api/mcp.html"}</li>
     *   <li>通过 MCP 协议调用：{@code scrapeWebPage("https://docs.spring.io/spring-ai/reference/api/mcp.html")}</li>
     *   <li>本方法抓取该页面的纯文本内容，截断后返回</li>
     *   <li>AI 模型基于完整的页面内容回答用户的深度问题</li>
     * </ol>
     *
     * <h3>局限性说明</h3>
     * <ul>
     *   <li>仅提取纯文本，不保留图片、视频、表格等非文本内容</li>
     *   <li>对于需要 JavaScript 渲染的 SPA（单页应用）网站，可能无法获取完整内容
     *       （Jsoup 是静态 HTML 解析器，不执行 JavaScript）</li>
     *   <li>某些网站可能有反爬虫机制，会拒绝 Jsoup 的请求</li>
     *   <li>返回内容被限制在 4000 字符以内，长文章会被截断</li>
     * </ul>
     *
     * @param url 要抓取的网页 URL，必须是完整的 HTTP 或 HTTPS 地址。
     *            例如：{@code "https://example.com/article"}。
     *            此参数由 AI 模型从搜索结果中选取或从用户消息中提取。
     *            通过 {@code @ToolParam} 注解向 AI 模型描述参数含义。
     * @return 网页的纯文本内容，最多 4000 字符。
     *         超过限制的内容被截断，末尾添加 "..." 标记。
     *         如果抓取失败（网络错误、URL 无效、网站拒绝访问等），返回：
     *         {@code "Error scraping web page: {错误详情}"}
     */
    @Tool(name = "scrapeWebPage", description = "Fetch and extract the text content from a web page URL. Returns up to 4000 characters of clean text. Use this after searchWeb to get the full content of a specific page.")
    public String scrapeWebPage(@ToolParam(description = "The URL of the web page to scrape") String url) {
        try {
            // 使用 Jsoup 连接到目标 URL 并解析 HTML 文档
            // Jsoup.connect(url).get() 会自动处理：
            // 1. HTTP 连接和请求
            // 2. 字符编码自动检测
            // 3. HTML 解析为 DOM 树
            Document doc = Jsoup.connect(url).get();

            // 提取整个 HTML 文档的纯文本内容
            // doc.text() 会自动去除所有 HTML 标签、脚本、样式，
            // 只保留用户可见的文本内容
            String text = doc.text();

            // 截断文本到最大允许长度
            return truncate(text, MAX_CONTENT_CHARS);
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }

    /**
     * <h3>截断文本到指定的最大字符数</h3>
     *
     * <p>
     * 此辅助方法用于控制返回文本的长度，防止返回过长内容导致以下问题：
     * </p>
     * <ul>
     *   <li>超出 AI 模型的上下文窗口</li>
     *   <li>消耗过多的 token（即 AI 的使用费用）</li>
     *   <li>响应时间过长</li>
     * </ul>
     *
     * <h3>截断规则</h3>
     * <ul>
     *   <li>如果文本为 {@code null}，直接返回 {@code null}</li>
     *   <li>如果文本长度小于等于 {@code maxChars}，返回原始文本（不做修改）</li>
     *   <li>如果文本长度超过 {@code maxChars}，截取前 {@code maxChars} 个字符，并在末尾添加 "..."</li>
     * </ul>
     *
     * @param text     待截断的原始文本，可能为 {@code null}
     * @param maxChars 最大允许的字符数，由常量 {@link #MAX_CONTENT_CHARS} 传入
     * @return 截断后的文本。如果原始文本为 {@code null}，返回 {@code null}；
     *         如果原始文本未超过限制，返回原始文本；
     *         如果原始文本超过限制，返回截断后的前 {@code maxChars} 个字符加 "..." 后缀
     */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        // substring(0, maxChars) 截取前 maxChars 个字符
        // 末尾添加 "..." 提示用户内容已被截断
        return text.substring(0, maxChars) + "...";
    }
}
