package cn.lwx.mcpserver.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class WeatherTool {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Tool(description = "查询指定城市的当前天气，返回天气状况、温度、湿度、风速。用于回答「今天适合约会吗」「周末天气怎么样」之类的问题。城市名用中文，如 北京、上海、深圳")
    public String getWeather(@ToolParam(description = "城市中文名，如 北京") String city) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://wttr.in/" + city + "?format=%C|%t|%h|%w&lang=zh"))
                    .header("User-Agent", "curl")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String[] parts = resp.body().split("\\|");
            if (parts.length < 4) return "无法获取天气信息";
            return String.format("城市: %s\n天气: %s\n温度: %s\n湿度: %s\n风速: %s",
                    city, parts[0].trim(), parts[1].trim(), parts[2].trim(), parts[3].trim());
        } catch (Exception e) {
            return "查询天气失败: " + e.getMessage();
        }
    }
}
