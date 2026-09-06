package cn.lwx.lwxaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.lwx.lwxaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <h2>资源下载工具类</h2>
 * <p>
 * 负责从网络 URL 下载图片等资源文件到本地，通过 Spring AI 的 {@link Tool @Tool} 注解
 * 将下载能力暴露给大语言模型（LLM）进行函数调用（Function Calling）。
 * </p>
 *
 * <h3>核心机制</h3>
 * <p>
 * 当 LLM 需要在回答中展示图片时（例如生成带图片的 PDF 报告），会先通过搜索工具获取图片 URL，
 * 然后调用本类的 {@link #downloadImages(String[], String[], Integer)} 批量下载图片到本地。
 * 下载成功后返回本地文件路径，LLM 可使用 Markdown 图片语法或
 * {@link PDFGenerationTool} 将图片嵌入最终输出。
 * </p>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li><b>不要虚构 URL</b>：LLM 应先通过搜索工具获取真实的图片 URL，再调用本工具下载</li>
 *   <li><b>并发限制</b>：批量下载最大并发数为 {@value #MAX_CONCURRENCY}，防止对源站造成过大压力</li>
 *   <li><b>反盗链处理</b>：自动从 URL 提取源站域名构造 Referer 请求头，绕过常见 CDN 防盗链机制</li>
 *   <li><b>超时控制</b>：支持自定义超时时间，默认 {@value #DEFAULT_TIMEOUT} 毫秒</li>
 *   <li><b>内容类型校验</b>：下载后检查 Content-Type 响应头，拒绝非图片资源</li>
 * </ul>
 *
 * <h3>工具注册</h3>
 * <p>
 * 本类不标注 {@code @Component}，而是在
 * {@link cn.lwx.lwxaiagent.tools.ToolRegistration#allTools(org.springframework.ai.tool.ToolCallbackProvider, KnowledgeSearchTool)}
 * 中手动 {@code new} 实例化后注册。
 * </p>
 *
 * @author lwx-ai-agent
 * @see PDFGenerationTool
 * @see FileConstant
 */
public class ResourceDownloadTool {

    /** 单文件下载默认超时时间（毫秒）：30 秒 */
    private static final int DEFAULT_TIMEOUT = 30000;
    /** HEAD 请求超时时间（毫秒）：8 秒，用于快速探测资源是否存在 */
    private static final int HEAD_TIMEOUT = 8000;
    /** 批量下载最大并发线程数：3，防止对源站造成过大压力 */
    private static final int MAX_CONCURRENCY = 3;
    /** User-Agent 请求头：模拟 Chrome 120 浏览器，避免被服务器拒绝 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    /**
     * <h3>下载单张图片</h3>
     * <p>
     * 从指定的 URL 下载一张图片到本地文件系统的 downloads 目录下。
     * LLM 在获取到单个图片的 URL 后，可调用此方法将其缓存到本地，以便后续渲染到 PDF 或直接展示。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>校验超时参数（null 或非正数使用默认值 {@value #DEFAULT_TIMEOUT} 毫秒）</li>
     *   <li>在文件保存目录下创建 {@code downloads/} 子目录</li>
     *   <li>调用 {@link #downloadSingle(String, String, int)} 执行实际下载</li>
     * </ol>
     *
     * @param url      图片的完整 HTTP/HTTPS URL，不能是虚构的，必须来自搜索结果
     * @param fileName 保存到本地的文件名，例如 "cover.jpg"、"image1.png"
     * @param timeout  下载超时时间（毫秒），可选参数，默认 {@value #DEFAULT_TIMEOUT}
     * @return 成功返回 "OK: /api/files/downloads/文件名"；
     *         失败返回以 "FAILED:" 开头的错误描述字符串，包含原因和原始 URL
     */
    @Tool(name = "downloadResource", description = "Download a single image from a URL. Returns the local file path on success. Call searchBaiduImages first to get real URLs — do NOT fabricate URLs.")
    public String downloadResource(
            @ToolParam(description = "The URL of the image to download") String url,
            @ToolParam(description = "File name to save as, e.g. image.jpg") String fileName,
            @ToolParam(description = "Timeout in milliseconds (optional, default 30000)") Integer timeout) {
        // 参数校验：null 或非正数则取默认超时
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        // 安全（2026-09-06 工具抽查）：URL 协议/内网校验 + 文件名清洗（防 SSRF/路径穿越）
        String whyUrl = cn.lwx.lwxaiagent.tools.ToolSafety.validateHttpUrl(url);
        if (whyUrl != null) {
            return "URL 不合法: " + whyUrl;
        }
        fileName = cn.lwx.lwxaiagent.tools.ToolSafety.sanitizeFileName(fileName);
        if (fileName.isBlank()) {
            return "文件名非法";
        }
        // 确保下载目录存在
        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);
        return downloadSingle(url, dir + fileName, t);
    }

    /**
     * <h3>批量下载图片（并发）</h3>
     * <p>
     * 使用固定线程池并发下载多张图片，每个 URL 对应一个文件名。
     * 这是 LLM 最常用的图片下载方式——在获取到多个搜索结果 URL 后，一次性批量下载。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>参数校验</b>：URL 数组长度必须与文件名数组长度一致</li>
     *   <li><b>线程池创建</b>：创建固定大小的线程池（线程数 = min(URL数量, MAX_CONCURRENCY)）</li>
     *   <li><b>任务提交</b>：为每个 URL 创建独立下载任务，分发到线程池执行</li>
     *   <li><b>结果收集</b>：等待所有任务完成（最多 120 秒），统计成功/失败数量</li>
     *   <li><b>格式化输出</b>：返回每个下载结果的详细列表和汇总统计</li>
     * </ol>
     *
     * <h4>注意事项</h4>
     * <ul>
     *   <li>批量下载完成后不要直接生成 PDF——仅在用户明确要求时才调用 PDF 工具</li>
     *   <li>下载完成后应使用 Markdown 图片语法 {@code ![](path)} 将图片展示给用户</li>
     *   <li>失败的下载会被自动跳过，不会中断整个批量任务</li>
     * </ul>
     *
     * @param urls      图片 URL 数组，长度必须与 {@code fileNames} 一致
     * @param fileNames 对应的本地文件名数组，长度必须与 {@code urls} 一致
     * @param timeout   单张图片下载超时（毫秒），可选参数，默认 {@value #DEFAULT_TIMEOUT}
     * @return 格式化字符串，包含每张图片的下载结果（OK/FAILED）+ 汇总统计（成功 X 张，失败 Y 张）
     */
    @Tool(name = "downloadImages", description = "Download multiple images concurrently. Pass URL array + file name array, returns local paths. After downloading, return the paths to the user directly using markdown image syntax `![](path)` so the images are displayed inline. Do NOT generate PDF unless the user explicitly asks for a PDF file. Failed downloads are skipped automatically.")
    public String downloadImages(
            @ToolParam(description = "Array of image URLs") String[] urls,
            @ToolParam(description = "Array of file names, must match URLs length") String[] fileNames,
            @ToolParam(description = "Timeout in ms per image (optional, default 15000)") Integer timeout) {
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        // 安全（2026-09-06 工具抽查）：URL 逐个协议/内网校验、文件名清洗（防 SSRF/路径穿越）
        for (int i = 0; i < urls.length; i++) {
            String whyUrl = cn.lwx.lwxaiagent.tools.ToolSafety.validateHttpUrl(urls[i]);
            if (whyUrl != null) {
                return "第 " + (i + 1) + " 个 URL 不合法: " + whyUrl;
            }
            if (fileNames[i] != null) {
                fileNames[i] = cn.lwx.lwxaiagent.tools.ToolSafety.sanitizeFileName(fileNames[i]);
            }
        }
        // 校验 URL 数量与文件名数量必须一致
        if (urls.length != fileNames.length) {
            return "Error: URLs count (" + urls.length + ") != file names count (" + fileNames.length + ")";
        }

        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);

        // 线程池大小 = min(URL 数量, 最大并发上限)
        int threads = Math.min(urls.length, MAX_CONCURRENCY);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        // 为每个 URL 构造独立的下载任务
        for (int i = 0; i < urls.length; i++) {
            String u = urls[i].trim();
            String path = dir + fileNames[i].trim();
            int finalT = t;
            tasks.add(() -> downloadSingle(u, path, finalT));
        }

        try {
            // 提交所有任务并等待完成（总超时 120 秒）
            List<java.util.concurrent.Future<String>> futures = executor.invokeAll(tasks, 120, TimeUnit.SECONDS);
            StringBuilder sb = new StringBuilder("Downloaded ").append(futures.size()).append(" images:\n");
            int ok = 0, fail = 0;
            for (int i = 0; i < futures.size(); i++) {
                String result = futures.get(i).get();
                sb.append("  [").append(i + 1).append("] ").append(result).append("\n");
                if (result.startsWith("OK:")) ok++;
                else fail++;
            }
            sb.append("Summary: ").append(ok).append(" succeeded, ").append(fail).append(" failed");
            return sb.toString();
        } catch (Exception e) {
            return "Error in batch download: " + e.getMessage();
        } finally {
            // 确保线程池资源被释放
            executor.shutdown();
        }
    }

    /**
     * <h3>执行单个资源下载</h3>
     * <p>
     * 核心下载逻辑，负责实际执行 HTTP 请求并将响应体保存为本地文件。
     * 包含反盗链绕过、内容类型校验和完整性检查。
     * </p>
     *
     * <h4>下载流程</h4>
     * <ol>
     *   <li><b>Referer 构造</b>：调用 {@link #extractOrigin(String)} 从 URL 提取源站域名，
     *       构造 Referer 请求头绕过防盗链机制</li>
     *   <li><b>HEAD 预检</b>（非致命）：发送 HEAD 请求探测资源是否存在，
     *       但即使 HEAD 失败也会继续尝试 GET（某些 CDN 会拒绝 HEAD 请求）</li>
     *   <li><b>GET 下载</b>：发送 GET 请求，设置 User-Agent 和 Referer 头</li>
     *   <li><b>Content-Type 校验</b>：检查响应是否为图片类型（image/* 或 application/octet-stream），
     *       拒绝非图片资源（例如反盗链返回的 HTML 页面）</li>
     *   <li><b>完整性检查</b>：验证文件是否成功写入且非空</li>
     * </ol>
     *
     * <h4>关于 HEAD 预检</h4>
     * <p>
     * HEAD 请求用于预先检查资源是否存在，但这不是致命步骤（失败也会继续尝试 GET）。
     * 因为某些 CDN（尤其是百度图片 CDN）会拒绝 HEAD 请求，但 GET 请求在正确设置
     * Referer 头后能正常获取。
     * </p>
     *
     * @param url      要下载的图片 URL
     * @param fullPath 本地保存的完整路径（含目录和文件名）
     * @param timeout  HTTP 请求超时时间（毫秒）
     * @return 成功返回 "OK: /api/files/downloads/文件名"；
     *         失败返回 "FAILED: 原因 — 原始URL"
     */
    private String downloadSingle(String url, String fullPath, int timeout) {
        // 从 URL 提取源站作为 Referer（绕过图片反盗链）
        String referer = extractOrigin(url);

        // HEAD 试探（非致命，失败也继续尝试 GET）
        try {
            int status = HttpRequest.head(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .timeout(HEAD_TIMEOUT)
                    .execute()
                    .getStatus();
            if (status >= 400) {
                // HEAD 返回 4xx，但继续尝试 GET（某些 CDN 会拒绝 HEAD）
            }
        } catch (Exception ignored) {
            // HEAD 可能被 CDN 封掉，不阻塞后续 GET
        }

        try {
            // 发送 GET 请求下载图片内容
            HttpResponse response = HttpRequest.get(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .timeout(timeout)
                    .execute();

            // Content-Type 校验：拒绝非图片响应（防盗链重定向到 HTML 等）
            String contentType = response.header("Content-Type");
            if (contentType != null && !contentType.isEmpty()
                    && !contentType.startsWith("image/")
                    && !contentType.startsWith("application/octet-stream")) {
                return "FAILED: not an image (Content-Type: " + contentType + ") — " + url;
            }

            // 将响应体写入本地文件
            response.writeBody(new File(fullPath));

            // 完整性检查：文件必须存在且非空
            if (!FileUtil.file(fullPath).exists() || FileUtil.file(fullPath).length() == 0) {
                return "FAILED: empty file — " + url;
            }

            // 返回本地文件的 HTTP 访问路径
            return "OK: /api/files/downloads/" + new File(fullPath).getName();
        } catch (Exception e) {
            return "FAILED: " + e.getMessage() + " — " + url;
        }
    }

    /**
     * <h3>提取源站域名作为 Referer</h3>
     * <p>
     * 从目标 URL 中提取协议 + 主机名 + 端口，构造合适的 Referer 请求头。
     * Referer 头用于绕过常见图片 CDN（内容分发网络）的反盗链检查。
     * </p>
     *
     * <h4>特殊处理：百度 CDN</h4>
     * <p>
     * 当目标 URL 的域名包含 {@code .baidu.com} 时，统一返回 {@code https://image.baidu.com/}
     * 作为 Referer。这是因为百度的图片 CDN 需要 Referer 指向 image.baidu.com 源站
     * 才能绕过反盗链机制，使用原始请求的域名作为 Referer 会被拒绝。
     * </p>
     *
     * <h4>标准处理</h4>
     * <p>
     * 对于非百度域名，使用标准逻辑构造 Referer：
     * </p>
     * <ul>
     *   <li>默认端口（80/443/-1）：Referer = 协议 + 主机名</li>
     *   <li>非默认端口：Referer = 协议 + 主机名 + 端口号</li>
     * </ul>
     *
     * @param url 目标图片的完整 URL
     * @return 构造好的 Referer 字符串；如果 URL 解析失败，返回默认值 {@code https://image.baidu.com}
     */
    private static String extractOrigin(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            // 百度图库 CDN 需要 Referer 指向 image.baidu.com 才能绕过反盗链
            if (host != null && (host.contains(".baidu.com") || host.equals("baidu.com"))) {
                return "https://image.baidu.com/";
            }
            int port = uri.getPort();
            // 标准端口（80, 443, -1 表示未指定）只返回协议+主机名
            if (port == 80 || port == 443 || port == -1) {
                return uri.getScheme() + "://" + host;
            }
            // 非标准端口需要显式添加端口号
            return uri.getScheme() + "://" + host + ":" + port;
        } catch (Exception e) {
            // URL 解析失败时的安全回退值
            return "https://image.baidu.com";
        }
    }
}
