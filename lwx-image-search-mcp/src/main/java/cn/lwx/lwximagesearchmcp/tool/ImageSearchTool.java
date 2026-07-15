package cn.lwx.lwximagesearchmcp.tool;

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

@Service
public class ImageSearchTool {


    private static final String API_KEY = "cUv3GAMzob8gBcUJazjF4F2wyo2IGuo7ObnwyEUhL8lcHbQdpBb2zZZG";


    private static final String API_URL = "https://api.pexels.com/v1/search";
    // 定义一个工具方法，接受搜索关键词作为参数，返回搜索结果的字符串表示。方法内部调用 searchMediumImages 方法获取图片 URL 列表，并将其连接成一个字符串返回。如果在搜索过程中发生异常，则返回错误信息。
    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        try {
            return String.join(",", searchMediumImages(query));
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }


    public List<String> searchMediumImages(String query) {
        // 设置请求头，包含 API Key
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", API_KEY);

        // 构建查询参数
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);

        // 发送 GET 请求到 Pexels API
        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();

        // 解析响应，提取中等尺寸图片的 URL
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
