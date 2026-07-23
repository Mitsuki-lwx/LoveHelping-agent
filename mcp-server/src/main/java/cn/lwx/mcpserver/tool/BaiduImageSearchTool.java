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

@Slf4j
@Component
public class BaiduImageSearchTool {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Search Baidu Images and return image URLs (URLs only, no download). Works best with specific Chinese keywords. Returns max 6 unique URLs. Do NOT fabricate URLs - only use real URLs returned by this tool.")
    public String searchBaiduImages(@ToolParam(description = "Search keyword for Baidu image search") String keyword) {
        try {
            log.info("Searching Baidu images for: {}", keyword);

            List<String> resultUrls = new ArrayList<>();
            int totalFound = 0;

            for (int page = 0; page < 3 && resultUrls.size() < 6; page++) {
                log.info("Fetching page {}", page + 1);

                if (page > 0) Thread.sleep(1500);
                List<String> pageUrls = fetchImageUrls(keyword, page * 30);

                for (String imageUrl : pageUrls) {
                    if (resultUrls.size() >= 6) break;
                    if (!resultUrls.contains(imageUrl)) {
                        resultUrls.add(imageUrl);
                    }
                    totalFound++;
                }
            }

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

    private List<String> fetchImageUrls(String keyword, int offset) throws Exception {
        List<String> urls = new ArrayList<>();

        String apiUrl = String.format(
                "https://image.baidu.com/search/acjson?tn=resultjson_com&logid=&ipn=&ct=201326592&" +
                        "is=&fp=result&queryWord=%s&cl=2&lm=-1&ie=utf-8&oe=utf-8&adpicid=&st=-1&z=&ic=0&hd=0&latest=0&" +
                        "copyright=0&word=%s&s=&se=&tab=&width=&height=&face=0&istype=2&qc=&nc=1&fr=&expermode=&" +
                        "force=&pn=%d&rn=30",
                URLEncoder.encode(keyword, "UTF-8"),
                URLEncoder.encode(keyword, "UTF-8"),
                offset
        );

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Referer", "https://image.baidu.com/search?word=" + URLEncoder.encode(keyword, "UTF-8"));
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try (InputStream in = conn.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (raw.trim().startsWith("<")) {
                log.warn("Baidu returned HTML, skipping page {}", offset / 30 + 1);
                return urls;
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(raw);
            } catch (Exception e) {
                log.warn("Baidu returned invalid JSON for keyword '{}': {}", keyword, e.getMessage());
                return urls;
            }
            JsonNode data = root.get("data");

            if (data != null && data.isArray()) {
                for (JsonNode item : data) {
                    String imageUrl = null;
                    JsonNode replaceUrl = item.get("replaceUrl");
                    if (replaceUrl != null && replaceUrl.isArray() && replaceUrl.size() > 0) {
                        imageUrl = getString(replaceUrl.get(0), "ObjURL");
                    }
                    if (imageUrl == null || imageUrl.isEmpty()) {
                        imageUrl = getString(item, "thumbURL");
                    }

                    if (imageUrl != null && imageUrl.startsWith("http") && !urls.contains(imageUrl)) {
                        urls.add(imageUrl);
                    }
                }
            }
        }

        log.info("Page {} returned {} images", offset / 30 + 1, urls.size());
        return urls;
    }

    private String getString(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

}
