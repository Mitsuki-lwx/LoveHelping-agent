package cn.lwx.mcpserver.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.StringJoiner;

/**
 * <h1>网络搜索工具（基于 Google Serper API）</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 通过调用 Google Serper API 来实现互联网搜索功能。
 * 标注了 {@code @Component} 注解，由 Spring 容器管理。
 * </p>
 *
 * <h2>Serper API 简介</h2>
 * <p>
 * Serper 是一个第三方 Google 搜索 API 服务（{@code https://google.serper.dev}），
 * 提供了简单快速的 Google 搜索结果获取能力。相比直接使用 Google Custom Search API，
 * Serper 的优势在于：
 * </p>
 * <ul>
 *   <li>无需配置 Google 自定义搜索引擎 ID（CX）</li>
 *   <li>返回结构化的 JSON 数据，包含标题、摘要、链接等</li>
 *   <li>价格相对便宜，免费额度充足</li>
 *   <li>API 设计简洁，易于集成</li>
 * </ul>
 *
 * <h2>在 AI 对话中的作用</h2>
 * <p>
 * AI 大语言模型的知识通常有截止日期限制，无法获取截止日期之后的最新信息。
 * 本工具为 AI 模型提供了实时联网搜索能力，使其能够：
 * </p>
 * <ul>
 *   <li>获取最新的新闻和时事</li>
 *   <li>查询实时数据（股价、天气、赛事结果等）</li>
 *   <li>搜索 AI 训练数据中不包含的专业知识</li>
 *   <li>验证和补充已有的知识</li>
 * </ul>
 *
 * <h2>与 WebScrapingTool 的协作</h2>
 * <p>
 * 本工具返回搜索结果摘要（标题、简介、URL），但不包含网页的完整内容。
 * 当 AI 模型需要深入了解某个搜索结果的详细内容时，
 * 会进一步调用 {@link cn.lwx.mcpserver.tool.WebScrapingTool WebScrapingTool} 来抓取完整网页。
 * 典型调用链：
 * </p>
 * <ol>
 *   <li>用户提问"最新的 Spring AI 更新内容是什么？"</li>
 *   <li>AI 模型调用本工具的 {@code searchWeb("Spring AI latest updates")}</li>
 *   <li>本工具返回 5 条搜索结果的摘要信息</li>
 *   <li>AI 模型从中选择最相关的 URL，调用 {@code WebScrapingTool#scrapeWebPage(url)} 抓取详细内容</li>
 *   <li>AI 模型基于完整的文章内容回答用户问题</li>
 * </ol>
 *
 * <h2>配置说明</h2>
 * <p>
 * API 密钥通过 Spring 的 {@code @Value} 注解从配置文件（如 {@code application.yml}）中注入：
 * </p>
 * <pre>{@code
 * search-api:
 *   api-key: your-serper-api-key
 * }</pre>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @see WebScrapingTool
 * @since 2025
 */
@Component
public class WebSearchTool {

    /**
     * Serper 搜索 API 的基础 URL。
     * 请求方式为 HTTP POST，请求体为 JSON 格式的查询参数。
     */
    private static final String SEARCH_API_URL = "https://google.serper.dev/search";

    /**
     * 每次搜索返回的最大结果数量。
     * 设定为 5 条，在信息充分性和 token 消耗之间取得平衡。
     * 5 条结果既能提供足够的参考信息，又不会占用过多的上下文空间。
     */
    private static final int DEFAULT_LIMIT = 5;

    /**
     * Java 11 的 HTTP 客户端实例，用于向 Serper API 发送 POST 请求。
     * 线程安全，可复用于多次请求。
     */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Jackson 的 JSON 解析器，用于解析 Serper API 返回的 JSON 响应。
     * 线程安全，可作为实例常量复用。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serper API 的认证密钥，从 Spring 配置文件中注入。
     *
     * <p>
     * 使用 {@code @Value("${search-api.api-key:}")} 注解从配置中读取，
     * {@code :} 后面的空字符串表示默认值——如果配置文件中未设置该属性，则使用空字符串。
     * 空字符串时会触发方法内的参数校验，返回友好的错误提示。
     * </p>
     *
     * <p>
     * 获取方式：访问 <a href="https://serper.dev">serper.dev</a> 注册并获取 API Key。
     * </p>
     */
    @Value("${search-api.api-key:}")
    private String apiKey;

    /**
     * <h3>搜索互联网信息（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具。
     * 当 AI 模型需要获取最新的互联网信息时，会调用此方法。
     * 适用于回答需要实时数据的各类问题。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li><b>参数校验</b>：检查查询关键词和 API 密钥是否为空，
     *       为空时返回友好的错误提示而非直接抛异常</li>
     *   <li><b>构建请求</b>：构造 JSON 格式的请求体（{@code {"q": "查询关键词"}}），
     *       设置必要的 HTTP 请求头（API Key 认证、Content-Type）</li>
     *   <li><b>发送请求</b>：通过 {@link HttpClient} 向 Serper API 发送 POST 请求</li>
     *   <li><b>响应验证</b>：检查响应体是否为空、是否为 HTML（HTML 通常表示 API Key 无效或过期）</li>
     *   <li><b>JSON 解析</b>：使用 Jackson 解析 JSON 响应，提取 {@code organic} 数组中的搜索结果</li>
     *   <li><b>结果截断</b>：最多返回前 5 条搜索结果（由 {@link #DEFAULT_LIMIT} 控制）</li>
     *   <li><b>格式化输出</b>：使用 {@link StringJoiner} 将多条结果以逗号分隔拼接</li>
     * </ol>
     *
     * <h3>Serper API 响应 JSON 结构</h3>
     * <pre>{@code
     * {
     *   "organic": [
     *     {
     *       "title": "页面标题",
     *       "link": "https://...",
     *       "snippet": "页面摘要描述",
     *       "position": 1
     *     },
     *     ...
     *   ]
     * }
     * }</pre>
     * <p>
     * 本方法提取 {@code organic} 数组中的每个完整对象（而非仅提取标题或链接），
     * 以保留尽可能多的结构化信息供 AI 模型分析和使用。
     * </p>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中问"最新的 Spring Boot 版本是多少？"时：
     * </p>
     * <ol>
     *   <li>AI 模型识别到这是一个需要实时信息的问答</li>
     *   <li>通过 MCP 协议发送工具调用：{@code searchWeb("Spring Boot latest version 2025")}</li>
     *   <li>本方法向 Serper API 发起 Google 搜索</li>
     *   <li>Serper API 返回搜索结果（包含标题、链接、摘要）</li>
     *   <li>本方法将结果格式化后返回给 AI 模型</li>
     *   <li>如果需要更多细节，AI 模型会进一步调用 {@code WebScrapingTool} 抓取具体页面</li>
     *   <li>AI 模型整合所有信息后回答用户"Spring Boot 的最新版本是 3.4.x..."</li>
     * </ol>
     *
     * <h3>错误处理</h3>
     * <p>
     * 本方法对以下异常情况进行了友好的错误处理（不抛出异常，返回错误描述字符串）：
     * </p>
     * <ul>
     *   <li>查询关键词为空 → {@code "Error searching Serper: query is required"}</li>
     *   <li>API 密钥未配置 → {@code "Error searching Serper: search-api.api-key is missing"}</li>
     *   <li>响应体为空 → {@code "Error searching Serper: empty response"}</li>
     *   <li>返回 HTML 而非 JSON → {@code "Error searching Serper: API returned HTML (possibly invalid API key or expired)"}</li>
     *   <li>无搜索结果 → 返回空字符串 {@code ""}</li>
     *   <li>网络或其他异常 → {@code "Error searching Serper: {异常详情}"}</li>
     * </ul>
     *
     * @param query 搜索关键词，使用英文可以获得更丰富的 Google 搜索结果。
     *              由 AI 模型从用户的自然语言问题中提取并翻译（如果需要）。
     *              例如用户问"最新的 Java 21 特性"，AI 可能传入 "Java 21 new features"。
     *              通过 {@code @ToolParam} 注解向 AI 模型描述参数含义。
     * @return 搜索结果的 JSON 字符串，格式为逗号分隔的 JSON 对象列表：
     *         <pre>{@code {"title":"...","link":"...","snippet":"..."}, {...}, ...}</pre>
     *         最多包含 5 条搜索结果（由 {@link #DEFAULT_LIMIT} 控制）。
     *         如果无搜索结果，返回空字符串 {@code ""}。
     *         如果发生错误，返回对应的错误描述字符串。
     */
    @Tool(description = "Search the internet for current information using Google Search. Returns up to 5 search results with titles, snippets, and URLs. Use this to find up-to-date information, news, or facts that are not in the local knowledge base.")
    public String searchWeb(@ToolParam(description = "Search query keyword") String query) {
        // 参数校验：查询关键词不能为空
        if (!StringUtils.hasText(query)) {
            return "Error searching Serper: query is required";
        }
        // 参数校验：API 密钥不能为空（需要在配置文件中设置 search-api.api-key）
        if (!StringUtils.hasText(apiKey)) {
            return "Error searching Serper: search-api.api-key is missing";
        }

        try {
            // 构建 JSON 格式的请求体：{"q": "搜索关键词"}
            String jsonBody = "{\"q\":\"" + query + "\"}";

            // 构建 HTTP POST 请求
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_API_URL))           // 设置 API 地址
                    .header("X-API-KEY", apiKey)               // Serper API 的认证头
                    .header("Content-Type", "application/json") // 请求体为 JSON 格式
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)) // POST 方式提交
                    .build();

            // 发送请求并获取字符串响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            // 响应验证：检查响应体是否为空
            if (body == null || body.trim().isEmpty()) {
                return "Error searching Serper: empty response";
            }
            // 响应验证：如果返回 HTML（以 '<' 开头），说明 API 调用失败
            // 常见原因是 API Key 无效或已过期
            if (body.trim().startsWith("<")) {
                return "Error searching Serper: API returned HTML (possibly invalid API key or expired)";
            }

            // 使用 Jackson 解析 JSON 响应
            JsonNode root = objectMapper.readTree(body);
            // 提取 organic 数组（Google 的自然搜索结果）
            JsonNode organic = root.get("organic");

            // 如果没有搜索结果，返回空字符串
            if (organic == null || !organic.isArray() || organic.isEmpty()) {
                return "";
            }

            // 使用 StringJoiner 拼接多条搜索结果
            // 分隔符为 ", "（逗号加空格），方便 AI 模型解析
            StringJoiner joiner = new StringJoiner(", ");

            // 限制返回结果数量：取 DEFAULT_LIMIT（5）和实际结果数量的较小值
            int limit = Math.min(DEFAULT_LIMIT, organic.size());
            for (int i = 0; i < limit; i++) {
                // 将每条搜索结果的完整 JSON 对象加入拼接器
                joiner.add(organic.get(i).toString());
            }
            return joiner.toString();
        } catch (Exception e) {
            // 捕获所有异常（网络异常、JSON 解析异常等）
            return "Error searching Serper: " + e.getMessage();
        }
    }
}
