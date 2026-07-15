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

    @Tool(name = "downloadResource", description = "Download a single image from a URL. Returns the local file path on success.")
    public String downloadResource(
            @ToolParam(description = "The URL of the image to download") String url,
            @ToolParam(description = "File name to save as, e.g. image.jpg") String fileName,
            @ToolParam(description = "Timeout in milliseconds (optional, default 30000)") Integer timeout) {
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);
        return downloadSingle(url, dir + fileName, t);
    }

    @Tool(name = "downloadImages", description = "Download multiple images concurrently. Input URLs and fileNames as comma-separated lists. Returns local paths for all.")
    public String downloadImages(
            @ToolParam(description = "Comma-separated image URLs") String urls,
            @ToolParam(description = "Comma-separated file names, must match URLs count") String fileNames,
            @ToolParam(description = "Timeout in ms per image (optional, default 15000)") Integer timeout) {
        int t = (timeout != null && timeout > 0) ? timeout : DEFAULT_TIMEOUT;
        String[] urlArr = urls.split(",");
        String[] nameArr = fileNames.split(",");
        if (urlArr.length != nameArr.length) {
            return "Error: URLs count (" + urlArr.length + ") != file names count (" + nameArr.length + ")";
        }

        String dir = FileConstant.FILE_SAVE_DIR + "/downloads/";
        FileUtil.mkdir(dir);

        int threads = Math.min(urlArr.length, MAX_CONCURRENCY);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < urlArr.length; i++) {
            String u = urlArr[i].trim();
            String path = dir + nameArr[i].trim();
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
        try {
            int status = HttpRequest.head(url)
                    .header("User-Agent", UA)
                    .timeout(5000)
                    .execute()
                    .getStatus();
            if (status >= 400) {
                return "FAILED: HTTP " + status + " — " + url;
            }
        } catch (Exception e) {
            return "FAILED: unreachable (" + e.getMessage() + ") — " + url;
        }

        try {
            HttpResponse response = HttpRequest.get(url)
                    .header("User-Agent", UA)
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

            return "OK: " + fullPath.replace("\\", "/");
        } catch (Exception e) {
            return "FAILED: " + e.getMessage() + " — " + url;
        }
    }
}
