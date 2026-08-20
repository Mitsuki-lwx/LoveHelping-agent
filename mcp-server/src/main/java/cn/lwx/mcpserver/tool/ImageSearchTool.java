package cn.lwx.mcpserver.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h1>Pexels 图片搜索工具</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 通过调用 Pexels 官方 API 来搜索高质量的国际图片资源。
 * 标注了 {@code @Service} 注解（等效于 {@code @Component}，语义上表示这是一个服务层组件），
 * 由 Spring 容器管理生命周期。
 * </p>
 *
 * <h2>Pexels API 简介</h2>
 * <p>
 * Pexels 是一个知名的免费高质量图片和图库平台，提供丰富的摄影作品和创意图片。
 * 其官方 API（{@code https://api.pexels.com/v1/search}）允许开发者通过 RESTful 接口进行图片搜索，
 * 返回结构化的 JSON 数据，包含图片的多种尺寸 URL（original、large、medium、small 等）。
 * </p>
 *
 * <h2>与其他图片搜索工具的区别</h2>
 * <ul>
 *   <li><b>BaiduImageSearchTool</b>：适合中文关键词搜索，返回百度图片的 URL</li>
 *   <li><b>ImageSearchTool（本类）</b>：适合国际化的高质量图片搜索，通过 Pexels 官方 API 获取</li>
 * </ul>
 * AI 模型会根据用户需求自动选择合适的图片搜索工具。
 *
 * <h2>MCP 工具调用机制</h2>
 * <ol>
 *   <li>Spring AI 框架扫描到 {@code @Tool} 注解的方法，将方法元数据注册到 MCP Server。</li>
 *   <li>AI 模型（MCP Client）连接后获取到工具列表，理解工具的功能和参数要求。</li>
 *   <li>当用户请求搜索图片时，AI 模型通过 MCP 协议发起调用，传入搜索关键词。</li>
 *   <li>本方法执行 HTTP 请求到 Pexels API，解析返回的 JSON 数据，提取图片 URL。</li>
 *   <li>结果通过 MCP 协议返回给 AI 模型，由 AI 呈现给用户。</li>
 * </ol>
 *
 * <h2>依赖说明</h2>
 * <p>
 * 本类使用了 Hutool 工具库（一个国产的 Java 工具集）来简化 HTTP 请求和 JSON 解析：
 * </p>
 * <ul>
 *   <li>{@link HttpUtil}：发送 HTTP GET 请求到 Pexels API</li>
 *   <li>{@link JSONUtil}：解析 Pexels API 返回的 JSON 字符串</li>
 *   <li>{@link StrUtil}：字符串工具类，用于过滤空白字符串</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @since 2025
 */
@Service
public class ImageSearchTool {

    /**
     * Pexels API 的认证密钥。
     * 通过 HTTP 请求头 {@code Authorization} 传递，用于身份验证和 API 配额管理。
     * 此密钥应妥善保管，不应泄露或提交到公开仓库。
     */
    private static final String API_KEY = "cUv3GAMzob8gBcUJazjF4F2wyo2IGuo7ObnwyEUhL8lcHbQdpBb2zZZG";

    /**
     * Pexels 图片搜索 API 的基础 URL。
     * 完整的请求格式为：{@code GET https://api.pexels.com/v1/search?query={关键词}}
     */
    private static final String API_URL = "https://api.pexels.com/v1/search";

    /**
     * <h3>搜索 Pexels 图片（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具。
     * 当 AI 模型需要获取与某个关键词相关的高质量图片时，会调用此方法。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>接收 AI 模型传入的搜索关键词（英文关键词效果最佳）</li>
     *   <li>设置 HTTP 请求头，包含 Pexels API 的认证密钥</li>
     *   <li>构建查询参数（query），发送 GET 请求到 Pexels API</li>
     *   <li>解析返回的 JSON 响应，提取 {@code photos} 数组中每张图片的 {@code src.medium} 字段</li>
     *   <li>过滤掉空白的 URL，收集所有有效的中尺寸图片 URL</li>
     *   <li>以逗号分隔的字符串格式返回所有图片 URL</li>
     * </ol>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中说"帮我找几张日落的图片"时：
     * </p>
     * <ol>
     *   <li>AI 模型分析用户需求，决定调用图片搜索工具</li>
     *   <li>通过 MCP 协议发送调用：{@code searchImage("sunset")}</li>
     *   <li>本方法向 Pexels API 发起请求，获取与 "sunset" 相关的图片</li>
     *   <li>返回逗号分隔的图片 URL 列表给 AI 模型</li>
     *   <li>AI 模型将 URL 呈现给用户或进一步处理（如下载、展示等）</li>
     * </ol>
     *
     * @param query 搜索关键词，建议使用英文以获得最佳搜索结果。
     *              由 AI 模型从用户问题中提取并翻译（如果需要）。
     *              例如用户说"找日落的图片"，AI 可能传入 "sunset"。
     *              此参数通过 {@code @ToolParam} 注解描述给 AI 模型。
     * @return 逗号分隔的图片 URL 字符串，例如：
     *         {@code "https://images.pexels.com/photos/xxx/pexels-photo-xxx.jpeg?auto=compress&cs=tinysrgb&h=350,..."}
     *         如果搜索失败（如网络错误、API 异常），返回错误信息字符串：
     *         {@code "Error search image: {错误详情}"}
     *         此返回值通过 MCP Server 发送回 AI 模型。
     */
    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            // 调用 searchMediumImages 获取中尺寸图片 URL 列表，然后用逗号拼接返回
            return String.join(",", searchMediumImages(query));
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }

    /**
     * <h3>搜索 Pexels 中尺寸图片的 URL 列表</h3>
     *
     * <p>
     * 此方法封装了与 Pexels API 的 HTTP 交互和 JSON 解析逻辑。
     * 它是 {@link #searchImage(String)} 的核心实现方法。
     * </p>
     *
     * <h3>API 请求详情</h3>
     * <ul>
     *   <li><b>请求方式</b>：HTTP GET</li>
     *   <li><b>请求 URL</b>：{@code https://api.pexels.com/v1/search}</li>
     *   <li><b>请求头</b>：{@code Authorization: {API_KEY}}（Bearer 认证方式）</li>
     *   <li><b>查询参数</b>：{@code query={搜索关键词}}</li>
     * </ul>
     *
     * <h3>JSON 响应解析路径</h3>
     * <p>
     * Pexels API 返回的 JSON 结构大致如下：
     * </p>
     * <pre>{@code
     * {
     *   "photos": [
     *     {
     *       "src": {
     *         "original": "https://...",
     *         "large2x": "https://...",
     *         "large": "https://...",
     *         "medium": "https://...",   // <-- 本方法提取此字段
     *         "small": "https://...",
     *         ...
     *       }
     *     },
     *     ...
     *   ]
     * }
     * }</pre>
     * <p>
     * 解析流程：{@code root -> photos[] -> 每个photo -> src -> medium}
     * </p>
     *
     * <h3>选择 medium 尺寸的原因</h3>
     * <p>
     * 中尺寸图片在质量和文件大小之间取得了良好的平衡，适合大多数使用场景。
     * 如果 AI 或下游应用需要更高分辨率的图片，可以自行修改为提取 {@code large} 或 {@code original} 字段。
     * </p>
     *
     * @param query 搜索关键词，由 {@link #searchImage(String)} 直接透传
     * @return 中尺寸图片 URL 的列表，按 Pexels 返回的原始顺序排列。
     *         列表可能为空（如果没有匹配的图片或请求失败）。
     *         使用 Java Stream API 进行函数式处理，过滤掉空字符串。
     */
    public List<String> searchMediumImages(String query) {
        // 构建 HTTP 请求头：设置 Pexels API 的认证密钥
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", API_KEY);

        // 构建查询参数：设置搜索关键词
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);

        // 使用 Hutool 的 HttpUtil 发送 GET 请求
        // createGet: 创建 GET 请求构建器
        // addHeaders: 添加请求头
        // form: 添加查询参数（会被拼接到 URL 的 query string 中）
        // execute: 执行请求
        // body: 获取响应体字符串
        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();

        // 使用 Java Stream API 解析 JSON 响应并提取图片 URL
        // 1. JSONUtil.parseObj: 解析 JSON 字符串为 JSONObject
        // 2. getJSONArray("photos"): 获取 photos 数组
        // 3. stream(): 转换为 Stream 流
        // 4. map: 将每个元素转为 JSONObject
        // 5. map: 提取 src 对象
        // 6. map: 提取 medium 字段（中尺寸图片 URL）
        // 7. filter: 过滤掉空白或空字符串
        // 8. collect: 收集为 List
        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}
