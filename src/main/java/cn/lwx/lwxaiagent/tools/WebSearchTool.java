package cn.lwx.lwxaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.StringJoiner;

public class WebSearchTool {
    private static final String SEARCH_API_URL = "https://google.serper.dev/search";
    private static final int DEFAULT_LIMIT = 5;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey;

    public WebSearchTool() {
    }

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search for information from Google Search Engine via Serper API")
    public String searchWeb(@ToolParam(description = "Search query keyword") String query) {
        if (!StringUtils.hasText(query)) {
            return "Error searching Serper: query is required";
        }
        if (!StringUtils.hasText(apiKey)) {
            return "Error searching Serper: search-api.api-key is missing";
        }

        try {
            String jsonBody = "{\"q\":\"" + query + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_API_URL))
                    .header("X-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode organic = root.get("organic");
            if (organic == null || !organic.isArray() || organic.isEmpty()) {
                return "";
            }

            StringJoiner joiner = new StringJoiner(", ");
            int limit = Math.min(DEFAULT_LIMIT, organic.size());
            for (int i = 0; i < limit; i++) {
                joiner.add(organic.get(i).toString());
            }
            return joiner.toString();
        } catch (Exception e) {
            return "Error searching Serper: " + e.getMessage();
        }
    }
}
