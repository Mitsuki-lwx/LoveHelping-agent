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

public class ResourceDownloadTool {

    private static final int DEFAULT_TIMEOUT = 30000;
    private static final int HEAD_TIMEOUT = 8000;
    private static final int MAX_CONCURRENCY = 3;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    @Tool(name = "downloadResource", description = "Download a single image from a URL. Returns the local file path on success. Call searchBaiduImages first to get real URLs — do NOT fabricate URLs.")
    public String downloadResource(
            @ToolParam(description = "The URL of the image to download") String url,
            @ToolParam(description = "File name to save as, e.g. image.jpg") String fileName,
            @ToolParam(description = "Timeout in milliseconds (optional, default 30000)") Integer timeout) {
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);
        return downloadSingle(url, dir + fileName, t);
    }

    @Tool(name = "downloadImages", description = "Download multiple images concurrently. Pass URL array + file name array, returns local paths. After downloading, return the paths to the user directly using markdown image syntax `![](path)` so the images are displayed inline. Do NOT generate PDF unless the user explicitly asks for a PDF file. Failed downloads are skipped automatically.")
    public String downloadImages(
            @ToolParam(description = "Array of image URLs") String[] urls,
            @ToolParam(description = "Array of file names, must match URLs length") String[] fileNames,
            @ToolParam(description = "Timeout in ms per image (optional, default 15000)") Integer timeout) {
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        if (urls.length != fileNames.length) {
            return "Error: URLs count (" + urls.length + ") != file names count (" + fileNames.length + ")";
        }

        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);

        int threads = Math.min(urls.length, MAX_CONCURRENCY);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < urls.length; i++) {
            String u = urls[i].trim();
            String path = dir + fileNames[i].trim();
            int finalT = t;
            tasks.add(() -> downloadSingle(u, path, finalT));
        }

        try {
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
            executor.shutdown();
        }
    }

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
                // HEAD 返回 4xx，但继续尝试 GET
            }
        } catch (Exception ignored) {
            // HEAD 可能被 CDN 封掉，不阻塞后续 GET
        }

        try {
            HttpResponse response = HttpRequest.get(url)
                    .header("User-Agent", UA)
                    .header("Referer", referer)
                    .timeout(timeout)
                    .execute();

            String contentType = response.header("Content-Type");
            if (contentType != null && !contentType.isEmpty()
                    && !contentType.startsWith("image/")
                    && !contentType.startsWith("application/octet-stream")) {
                return "FAILED: not an image (Content-Type: " + contentType + ") — " + url;
            }

            response.writeBody(new File(fullPath));

            if (!FileUtil.file(fullPath).exists() || FileUtil.file(fullPath).length() == 0) {
                return "FAILED: empty file — " + url;
            }

            return "OK: /api/files/downloads/" + new File(fullPath).getName();
        } catch (Exception e) {
            return "FAILED: " + e.getMessage() + " — " + url;
        }
    }

    /**
     * 从 URL 提取域名作为 Referer，绕过常见图片 CDN 的反盗链检查
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
            if (port == 80 || port == 443 || port == -1) {
                return uri.getScheme() + "://" + host;
            }
            return uri.getScheme() + "://" + host + ":" + port;
        } catch (Exception e) {
            return "https://image.baidu.com";
        }
    }
}
