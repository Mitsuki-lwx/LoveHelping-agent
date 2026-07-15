package cn.lwx.lwxaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class WebScrapingTool {
    private static final int MAX_CONTENT_CHARS = 4000;

    @Tool(name = "scrapeWebPage", description = "Scrape content from a web page")
    public String scrapeWebPage(@ToolParam(description = "The URL of the web page to scrape") String url) {
        // 这里可以添加实际的网页爬取逻辑
        // 例如，使用Jsoup库来爬取网页内容

        try {
            Document doc = Jsoup.connect(url).get();
            String text = doc.text();
            return truncate(text, MAX_CONTENT_CHARS);
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
