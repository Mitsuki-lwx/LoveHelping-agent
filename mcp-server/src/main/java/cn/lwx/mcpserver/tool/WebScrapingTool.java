package cn.lwx.mcpserver.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WebScrapingTool {
    private static final int MAX_CONTENT_CHARS = 4000;

    @Tool(name = "scrapeWebPage", description = "Fetch and extract the text content from a web page URL. Returns up to 4000 characters of clean text. Use this after searchWeb to get the full content of a specific page.")
    public String scrapeWebPage(@ToolParam(description = "The URL of the web page to scrape") String url) {
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
