package cn.lwx.mcpserver.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * <h1>天气查询工具</h1>
 *
 * <p>
 * 本类是一个 MCP（模型上下文协议，Model Context Protocol）工具类，
 * 通过调用免费天气服务 {@code wttr.in} 来查询指定城市的实时天气信息。
 * 标注了 {@code @Component} 注解，由 Spring 容器管理。
 * </p>
 *
 * <h2>wttr.in 服务简介</h2>
 * <p>
 * wttr.in 是一个免费的天气查询 Web 服务，提供了简单直观的 HTTP API 接口。
 * 通过构造特定格式的 URL，可以获取纯文本格式的天气数据，无需注册或 API Key。
 * 服务地址格式：
 * </p>
 * <pre>{@code https://wttr.in/{城市名}?format=%C|%t|%h|%w&lang=zh}</pre>
 *
 * <h2>format 参数说明</h2>
 * <p>
 * wttr.in 的 {@code format} 参数使用自定义的占位符来指定返回格式：
 * </p>
 * <ul>
 *   <li><b>%C</b>：天气状况描述文本（如"晴"、"多云"、"小雨"）</li>
 *   <li><b>%t</b>：当前温度（含单位，如"+25°C"）</li>
 *   <li><b>%h</b>：当前湿度百分比（如"65%"）</li>
 *   <li><b>%w</b>：当前风速（如"15km/h"）</li>
 *   <li><b>|</b>：分隔符，本工具使用 {@code |} 来分隔各个数据项，方便解析</li>
 *   <li><b>lang=zh</b>：语言参数，设为中文（zh），使天气描述以中文返回</li>
 * </ul>
 *
 * <h2>与其他工具的协作</h2>
 * <p>
 * 本工具被设计为与 {@link cn.lwx.mcpserver.tool.DatePlannerTool DatePlannerTool} 配合使用。
 * 典型的调用链：
 * </p>
 * <ol>
 *   <li>用户询问"周末北京适合约会吗？"</li>
 *   <li>AI 模型首先调用本工具的 {@code getWeather("北京")} 获取天气信息</li>
 *   <li>拿到天气结果后，AI 模型将 {@code weatherCondition} 参数传递给 {@code DatePlannerTool#planDate}</li>
 *   <li>{@code DatePlannerTool} 根据天气状况推荐室内或室外约会方案</li>
 * </ol>
 *
 * <h2>使用的 Java HTTP Client</h2>
 * <p>
 * 本工具使用 Java 11 引入的 {@link java.net.http.HttpClient} 发送 HTTP 请求。
 * 相比传统的 {@link java.net.HttpURLConnection}，新 HTTP Client 具有以下优势：
 * </p>
 * <ul>
 *   <li>支持 HTTP/2 协议</li>
 *   <li>异步请求支持</li>
 *   <li>更简洁的 Builder 模式 API</li>
 *   <li>内置的请求/响应体处理器</li>
 * </ul>
 *
 * @author lwx
 * @version 1.0
 * @see Tool
 * @see ToolParam
 * @since 2025
 */
@Component
public class WeatherTool {

    /**
     * Java 11 的 HTTP 客户端实例，用于发送天气查询请求。
     * {@link HttpClient#newHttpClient()} 创建的实例是线程安全的，可以复用。
     * 设置为 {@code final} 表示在对象构造后不可重新赋值。
     */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * <h3>查询指定城市的当前天气（MCP 工具方法）</h3>
     *
     * <p>
     * 本方法通过 {@code @Tool} 注解暴露为 MCP 工具。
     * 当 AI 模型需要获取某个城市的实时天气信息时，会调用此方法。
     * 适用于回答诸如"今天适合约会吗"、"周末天气怎么样"、"北京明天会下雨吗"等问题。
     * </p>
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>接收 AI 模型传入的城市名称（中文，如"北京"、"上海"）</li>
     *   <li>构建 wttr.in 的请求 URL，设置 format 参数指定返回格式为：{@code 天气|温度|湿度|风速}</li>
     *   <li>设置 {@code lang=zh} 使天气描述以中文返回</li>
     *   <li>设置 {@code User-Agent} 为 {@code "curl"}，因为 wttr.in 对 curl 用户代理返回纯文本格式</li>
     *   <li>发送 HTTP GET 请求，获取响应体字符串</li>
     *   <li>使用 {@code |} 分隔符解析响应体，提取天气、温度、湿度、风速四个字段</li>
     *   <li>将解析结果格式化为可读的文本返回</li>
     * </ol>
     *
     * <h3>响应格式示例</h3>
     * <p>
     * wttr.in 的原始响应（经过 lang=zh 和自定义 format 处理后）：
     * </p>
     * <pre>{@code 晴|+25°C|65%|15km/h}</pre>
     * <p>
     * 经过本方法解析和格式化后的返回结果：
     * </p>
     * <pre>{@code
     * 城市: 北京
     * 天气: 晴
     * 温度: +25°C
     * 湿度: 65%
     * 风速: 15km/h
     * }</pre>
     *
     * <h3>MCP Client 调用示例</h3>
     * <p>
     * 当用户在 AI 对话中说"北京的天气怎么样？"时：
     * </p>
     * <ol>
     *   <li>AI 模型理解用户意图，识别需要查询"北京"的天气</li>
     *   <li>通过 MCP 协议发送工具调用：{@code getWeather("北京")}</li>
     *   <li>本方法向 wttr.in 发起 HTTP 请求，获取天气数据</li>
     *   <li>将格式化的天气信息返回给 AI 模型</li>
     *   <li>AI 模型将天气信息以自然语言呈现给用户，如"北京今天天气晴，温度25°C..."</li>
     * </ol>
     *
     * <h3>注意事项</h3>
     * <ul>
     *   <li>城市名称必须使用中文（如"北京"而非"Beijing"），因为中文城市的 URL 编码在 wttr.in 中识别度更高</li>
     *   <li>如果 wttr.in 返回的数据不足 4 个字段（网络问题或城市名无效），返回"无法获取天气信息"</li>
     *   <li>wttr.in 是一个免费服务，没有请求频率限制，但也没有 SLA 保证，偶尔可能不可用</li>
     * </ul>
     *
     * @param city 要查询天气的城市名称，使用中文。
     *             例如："北京"、"上海"、"深圳"、"杭州"。
     *             支持中国城市，也支持国际城市的中文名。
     *             此参数由 AI 模型从用户的自然语言问题中自动提取。
     *             通过 {@code @ToolParam} 注解向 AI 模型描述参数含义。
     * @return 格式化的天气信息字符串，包含以下字段（每行一个）：
     *         <ul>
     *           <li>城市名称</li>
     *           <li>天气状况（如"晴"、"多云"、"小雨"）</li>
     *           <li>当前温度（含°C单位）</li>
     *           <li>空气湿度（百分比）</li>
     *           <li>风速（含单位 km/h）</li>
     *         </ul>
     *         如果查询失败或响应数据不完整，返回以下错误信息之一：
     *         <ul>
     *           <li>{@code "无法获取天气信息"} — 响应数据字段不足 4 个</li>
     *           <li>{@code "查询天气失败: {详细错误}"} — 发生网络异常或其他错误</li>
     *         </ul>
     */
    @Tool(description = "查询指定城市的当前天气，返回天气状况、温度、湿度、风速。用于回答「今天适合约会吗」「周末天气怎么样」之类的问题。城市名用中文，如 北京、上海、深圳")
    public String getWeather(@ToolParam(description = "城市中文名，如 北京") String city) {
        try {
            // 构建 HTTP GET 请求
            // URI 中的格式参数：%C=天气状况，%t=温度，%h=湿度，%w=风速，用 | 分隔各字段
            // lang=zh 确保天气描述是中文
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://wttr.in/" + city + "?format=%C|%t|%h|%w&lang=zh"))
                    .header("User-Agent", "curl")  // wttr.in 对 curl UA 返回纯文本
                    .GET()
                    .build();

            // 发送请求并获取字符串格式的响应体
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 使用 | 分隔符解析响应体
            // 预期格式：晴|+25°C|65%|15km/h
            String[] parts = resp.body().split("\\|");  // | 是正则特殊字符，需要转义

            // 验证解析结果：如果字段不足 4 个，说明数据不完整，无法正常显示
            if (parts.length < 4) return "无法获取天气信息";

            // 格式化输出，每行显示一个天气指标
            return String.format("城市: %s\n天气: %s\n温度: %s\n湿度: %s\n风速: %s",
                    city, parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim());
        } catch (Exception e) {
            // 捕获所有可能的异常（网络超时、DNS 解析失败、JSON 解析错误等）
            return "查询天气失败: " + e.getMessage();
        }
    }
}
