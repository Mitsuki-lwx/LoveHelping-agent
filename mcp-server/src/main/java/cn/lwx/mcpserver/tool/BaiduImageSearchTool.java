package cn.lwx.mcpserver.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;

/**
 * <h1>百度图片搜索工具</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 通过解析百度图片搜索的内部 JSON API 接口来获取图片 URL。
 * 标注了 {@code @Component} 注解，由 Spring 容器管理生命周期；
 * 同时通过 {@code @Tool} 注解将方法暴露为 MCP 工具，供 AI 模型（MCP Client）远程调用。
 * </p>
 *
 * <h2>MCP 工具调用机制</h2>
 * <ol>
 *   <li>Spring AI 框架在启动时扫描所有 {@code @Tool} 注解的方法，将其元数据（方法名、描述、参数信息）注册到 MCP Server。</li>
 *   <li>当 AI 模型（如 Claude）连接到 MCP Server 时，它会获取到所有可用工具的列表及描述。</li>
 *   <li>AI 模型根据用户的自然语言请求，自主决定是否调用某个工具、以及传递什么参数。</li>
 *   <li>MCP Server 接收到调用请求后，反射执行对应的 {@code @Tool} 方法，并将返回值发送回 AI 模型。</li>
 *   <li>AI 模型收到工具返回的结果后，将其整合到自己的回答中呈现给最终用户。</li>
 * </ol>
 *
 * <h2>百度图片搜索 API 说明</h2>
 * <p>
 * 本工具使用百度图片搜索的内部 AJAX 接口：
 * {@code https://image.baidu.com/search/acjson}，
 * 该接口返回 JSON 格式数据，包含图片的缩略图和原始图 URL。
 * </p>
 *
 * <h2>核心特性</h2>
 * <ul>
 *   <li><b>中文关键词优化</b>：尤其适合中文搜索场景，例如搜索"故宫"、"长城"等</li>
 *   <li><b>分页获取</b>：最多翻 3 页，每页 30 条，确保有足够的图片可供选择</li>
 *   <li><b>自动去重</b>：使用 {@link ArrayList#contains} 去重，确保返回的 URL 不重复</li>
 *   <li><b>频率控制</b>：翻页间隔 1500 毫秒（1.5 秒），避免触发百度的反爬虫机制</li>
 *   <li><b>最多 6 张</b>：只返回最多 6 张不重复的图片 URL，避免返回过多无用链接</li>
 *   <li><b>异常容错</b>：当百度返回 HTML（而非 JSON）时自动跳过该页</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @since 2025
 */
@Slf4j
@Component
public class BaiduImageSearchTool {

    /**
     * Jackson 的 JSON 解析器，用于解析百度图片搜索 API 返回的 JSON 数据。
     * 线程安全，因此可以作为实例常量复用。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * <h3>搜索百度图片（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具，
     * 当 AI 模型需要获取与某个关键词相关的真实图片链接时，会调用此方法。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>接收 AI 模型传入的搜索关键词</li>
     *   <li>循环调用百度图片 API，每次翻一页（30 条），最多翻 3 页</li>
     *   <li>翻页之间等待 1500 毫秒，避免触发反爬虫机制</li>
     *   <li>对每次获取的图片 URL 进行去重校验，只保留不重复的 URL</li>
     *   <li>一旦收集到 6 张不重复的图片 URL 即停止翻页</li>
     *   <li>将结果格式化为结构化文本返回给 AI 模型</li>
     * </ol>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中说"帮我找几张故宫的图片"时：
     * </p>
     * <ol>
     *   <li>AI 模型理解用户意图后，通过 MCP 协议发送工具调用：{@code searchBaiduImages("故宫")}</li>
     *   <li>本方法执行并返回格式化的图片 URL 列表</li>
     *   <li>AI 模型将图片 URL 列表呈现给用户</li>
     * </ol>
     *
     * @param keyword 百度图片搜索的关键词，建议使用中文。由 AI 模型从用户问题中提取。
     *                例如用户问"找一张长城的图片"，AI 会传入 "长城"。
     *                此参数通过 {@code @ToolParam} 注解描述给 AI 模型，帮助 AI 理解如何正确地传递参数。
     * @return 格式化的搜索结果字符串，包含：
     *         <ul>
     *           <li>搜索关键词</li>
     *           <li>总共找到的图片数量和实际返回的数量</li>
     *           <li>编号的图片 URL 列表（最多 6 条）</li>
     *           <li>下载图片的提示说明</li>
     *         </ul>
     *         如果搜索失败，返回以 "Search failed:" 开头的错误信息字符串。
     *         此返回值将被 MCP Server 直接发送回 AI 模型，供 AI 整合到回答中。
     */
    @Tool(description = "Search Baidu Images and return image URLs (URLs only, no download). Works best with specific Chinese keywords. Returns max 6 unique URLs. Do NOT fabricate URLs - only use real URLs returned by this tool.")
    public String searchBaiduImages(@ToolParam(description = "Search keyword for Baidu image search") String keyword) {
        try {
            log.info("Searching Baidu images for: {}", keyword);

            // 存储去重后的图片 URL 列表
            List<String> resultUrls = new ArrayList<>();
            // 统计总共找到的图片数量（包括重复的）
            int totalFound = 0;

            // 最多翻 3 页（页面索引 0, 1, 2），且结果不足 6 张时继续翻页
            for (int page = 0; page < 3 && resultUrls.size() < 6; page++) {
                log.info("Fetching page {}", page + 1);

                // 翻页间等待 1.5 秒，避免触发百度的请求频率限制
                if (page > 0) Thread.sleep(1500);
                // 调用百度图片 API，获取当前页的图片 URL 列表（每页 30 条）
                List<String> pageUrls = fetchImageUrls(keyword, page * 30);

                // 逐张图片进行去重处理
                for (String imageUrl : pageUrls) {
                    // 若已收集到 6 张不重复的图片，停止处理
                    if (resultUrls.size() >= 6) break;
                    // 只添加不重复的 URL（避免同一图片的缩略图和原图都被收录）
                    if (!resultUrls.contains(imageUrl)) {
                        resultUrls.add(imageUrl);
                    }
                    totalFound++;
                }
            }

            // 构建格式化的返回结果
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Search completed for '%s'. Found %d images (showing first %d):\n",
                    keyword, totalFound, resultUrls.size()));
            for (int i = 0; i < resultUrls.size(); i++) {
                sb.append(String.format("  [%d] %s\n", i + 1, resultUrls.get(i)));
            }
            sb.append("\nUse downloadImages tool to download the images you need (recommend 2-3 most relevant ones).");

            return sb.toString();

        } catch (Exception e) {
            log.error("Search failed", e);
            return "Search failed: " + e.getMessage();
        }
    }

    /**
     * <h3>从百度图片 API 获取指定分页的图片 URL 列表</h3>
     *
     * <p>
     * 此方法向百度图片搜索的内部 JSON API 发送 HTTP GET 请求，
     * 解析返回的 JSON 数据，提取图片 URL。
     * </p>
     *
     * <h3>API 地址与参数说明</h3>
     * <ul>
     *   <li><b>基础 URL</b>：{@code https://image.baidu.com/search/acjson}</li>
     *   <li><b>tn</b>：固定为 {@code resultjson_com}，表示返回 JSON 格式数据</li>
     *   <li><b>queryWord / word</b>：搜索关键词（URL 编码后），两个参数通常设为相同值</li>
     *   <li><b>pn</b>：偏移量，即从第几条结果开始返回。例如 {@code pn=30} 从第 31 条开始</li>
     *   <li><b>rn</b>：每页返回的结果数量，固定为 30</li>
     * </ul>
     *
     * <h3>反爬虫策略</h3>
     * <p>
     * 设置了完整的浏览器请求头（User-Agent、Referer、Accept 等）
     * 来模拟真实的浏览器请求，避免被百度识别为爬虫而返回 HTML 页面。
     * </p>
     *
     * <h3>JSON 解析逻辑</h3>
     * <ol>
     *   <li>首先检查返回内容是否为 HTML（以 {@code '<'} 开头），如果是 HTML 说明触发了反爬虫验证，跳过该页</li>
     *   <li>解析 JSON 后获取 {@code data} 数组，每个元素代表一张图片</li>
     *   <li>优先从 {@code replaceUrl[0].ObjURL} 获取高质量图片链接</li>
     *   <li>如果高质量链接不可用，回退到 {@code thumbURL}（缩略图链接）</li>
     *   <li>只保留以 {@code http} 开头的有效 URL</li>
     *   <li>自动去除当前页内的重复 URL</li>
     * </ol>
     *
     * @param keyword 搜索关键词
     * @param offset  结果偏移量（即从第几条开始返回），通常为 {@code page * 30}
     *                例如第一页传 0，第二页传 30，第三页传 60
     * @return 当前页中提取到的有效图片 URL 列表（已去重），列表可能为空
     * @throws Exception 网络连接异常、JSON 解析异常等各种异常都会被上层调用者捕获
     */
    private List<String> fetchImageUrls(String keyword, int offset) throws Exception {
        List<String> urls = new ArrayList<>();

        // 构建百度图片搜索的 API URL，keyword 需要 URL 编码以处理中文字符
        String apiUrl = String.format(
                "https://image.baidu.com/search/acjson?tn=resultjson_com&logid=&ipn=&ct=201326592&" +
                        "is=&fp=result&queryWord=%s&cl=2&lm=-1&ie=utf-8&oe=utf-8&adpicid=&st=-1&z=&ic=0&hd=0&latest=0&" +
                        "copyright=0&word=%s&s=&se=&tab=&width=&height=&face=0&istype=2&qc=&nc=1&fr=&expermode=&" +
                        "force=&pn=%d&rn=30",
                URLEncoder.encode(keyword, "UTF-8"),
                URLEncoder.encode(keyword, "UTF-8"),
                offset
        );

        // 创建 HTTP 连接，模拟真实浏览器请求
        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("GET");
        // 设置 User-Agent 头，模拟 Chrome 浏览器
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        // 设置 Referer 头，模拟从百度图片首页发起的请求
        conn.setRequestProperty("Referer", "https://image.baidu.com/search?word=" + URLEncoder.encode(keyword, "UTF-8"));
        // 设置期望的响应格式为 JSON
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        // 设置接受中文语言
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 设置连接超时为 10 秒
        conn.setConnectTimeout(10000);
        // 设置读取超时为 10 秒
        conn.setReadTimeout(10000);

        // 使用 try-with-resources 确保 InputStream 被正确关闭
        try (InputStream in = conn.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            // 使用 UTF-8 解码响应内容
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            // 如果响应以 '<' 开头，说明百度返回了 HTML 页面（可能是反爬验证页）
            if (raw.trim().startsWith("<")) {
                log.warn("Baidu returned HTML, skipping page {}", offset / 30 + 1);
                return urls;
            }
            JsonNode root;
            try {
                // 尝试解析 JSON
                root = objectMapper.readTree(raw);
            } catch (Exception e) {
                log.warn("Baidu returned invalid JSON for keyword '{}': {}", keyword, e.getMessage());
                return urls;
            }
            // 获取 data 字段，该字段是包含图片信息的数组
            JsonNode data = root.get("data");

            if (data != null && data.isArray()) {
                // 遍历 data 数组中的每个图片项
                for (JsonNode item : data) {
                    String imageUrl = null;
                    // 优先尝试获取高质量原图 URL
                    // replaceUrl 是一个数组，第一个元素的 ObjURL 字段通常是原图地址
                    JsonNode replaceUrl = item.get("replaceUrl");
                    if (replaceUrl != null && replaceUrl.isArray() && replaceUrl.size() > 0) {
                        imageUrl = getString(replaceUrl.get(0), "ObjURL");
                    }
                    // 如果高质量原图链接不可用，回退到缩略图 URL
                    if (imageUrl == null || imageUrl.isEmpty()) {
                        imageUrl = getString(item, "thumbURL");
                    }

                    // 只保留有效的 HTTP/HTTPS URL，且当前页内去重
                    if (imageUrl != null && imageUrl.startsWith("http") && !urls.contains(imageUrl)) {
                        urls.add(imageUrl);
                    }
                }
            }
        }

        log.info("Page {} returned {} images", offset / 30 + 1, urls.size());
        return urls;
    }

    /**
     * <h3>从 JSON 节点中安全地获取字符串字段值</h3>
     *
     * <p>
     * 此辅助方法用于从 {@link JsonNode} 中提取指定字段的字符串值，
     * 并处理字段不存在或值为 {@code null} 的情况，避免产生 {@link NullPointerException}。
     * </p>
     *
     * @param node  要从中提取字段的 JSON 节点对象，可能是任意层级的 JSON 对象
     * @param field 要提取的字段名称，例如 {@code "ObjURL"} 或 {@code "thumbURL"}
     * @return 字段对应的字符串值；如果节点为 null、字段不存在或字段值为 null，则返回 {@code null}
     */
    private String getString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

}
