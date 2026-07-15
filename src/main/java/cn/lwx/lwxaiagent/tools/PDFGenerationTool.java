package cn.lwx.lwxaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.lwx.lwxaiagent.constant.FileConstant;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.List;
import com.itextpdf.layout.element.ListItem;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class PDFGenerationTool {

    private static final float TITLE_FONT_SIZE = 24;
    private static final float H1_FONT_SIZE = 18;
    private static final float H2_FONT_SIZE = 15;
    private static final float H3_FONT_SIZE = 13;
    private static final float BODY_FONT_SIZE = 11;

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^[-*]\\s+(.+)$");
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\d+\\.\\s+(.+)$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^---\\s*$");
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^!\\[(.*?)\\]\\((.*?)\\)$");

    @Tool(description = "Generate a styled PDF file with markdown content. Supports headings, bold, lists, and images.")
    public String generatePDF(
            @ToolParam(description = "File name (e.g. plan.pdf)") String fileName,
            @ToolParam(description = "Content in markdown format") String content,
            @ToolParam(description = "Path or URL of an image to include at the top (optional)") String imagePath) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        try {
            FileUtil.mkdir(fileDir);

            content = removeEmoji(content);

            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {

                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                document.setMargins(50, 50, 50, 50);

                String title = extractTitle(content);
                if (title != null) {
                    Paragraph coverTitle = new Paragraph(title)
                            .setFont(font)
                            .setFontSize(TITLE_FONT_SIZE)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginTop(150);
                    document.add(coverTitle);

                    Paragraph coverDate = new Paragraph(java.time.LocalDate.now().toString())
                            .setFont(font)
                            .setFontSize(12)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginTop(20);
                    document.add(coverDate);
                    document.add(new Paragraph("\n"));
                }

                if (imagePath != null && !imagePath.isBlank()) {
                    try {
                        Image img = new Image(ImageDataFactory.create(imagePath));
                        img.setMaxWidth(UnitValue.createPercentValue(80));
                        img.setAutoScale(true);
                        document.add(img);
                        document.add(new Paragraph("\n"));
                    } catch (Exception e) {
                        log.warn("封面图片加载失败, path={}: {}", imagePath, e.getMessage());
                    }
                }

                renderMarkdown(document, content, font);

            }
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            String urlPath = "/api/files/pdf/" + encodedFileName;
            return "✅ PDF 已生成！\n\n📄 [" + fileName + "](" + urlPath + ")\n\n点击上方链接可在浏览器中预览或下载。";
        } catch (IOException e) {
            return "❌ PDF 生成失败：" + e.getMessage();
        }
    }

    private String removeEmoji(String content) {
        if (content == null) return null;
        // 移除会导致 STSong 字体编码问题的 emoji 字符
        String cleaned = content
                .replaceAll("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", "")  // 代理对（大部分 emoji）
                .replaceAll("[\\u2600-\\u27BF]", "")  // 杂项符号 & 丁贝符
                .replaceAll("[\\u2300-\\u23FF]", "")  // 杂项技术符号
                .replaceAll("[\\uFE00-\\uFE0F]", "")  // 变体选择器
                .replaceAll("[\\u200D]", "")  // 零宽连字
                .replaceAll("\\|", " ");  // 表格竖线替换为空格（PDF 不支持 markdown 表格）
        // 折叠多个空格为一个
        return cleaned.replaceAll("\\s{3,}", "  ").trim();
    }

    private String extractTitle(String content) {
        Matcher m = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    private void renderMarkdown(Document document, String content, PdfFont font) {
        String[] lines = content.split("\n");
        List currentList = null;
        boolean orderedList = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                continue;
            }

            if (HORIZONTAL_RULE.matcher(line).matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                Div hr = new Div();
                hr.setHeight(1);
                hr.setBackgroundColor(ColorConstants.LIGHT_GRAY);
                hr.setMarginTop(10);
                hr.setMarginBottom(10);
                document.add(hr);
                continue;
            }

            Matcher imgMatcher = IMAGE_PATTERN.matcher(line);
            if (imgMatcher.matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                String imgPath = imgMatcher.group(2);
                try {
                    Image img = new Image(ImageDataFactory.create(imgPath));
                    img.setMaxWidth(UnitValue.createPercentValue(90));
                    img.setAutoScale(true);
                    document.add(img);
                } catch (Exception e) {
                    log.warn("PDF markdown 内嵌图片加载失败, path={}: {}", imgPath, e.getMessage());
                }
                continue;
            }

            Matcher hMatcher = HEADING_PATTERN.matcher(line);
            if (hMatcher.matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                String level = hMatcher.group(1);
                String text = hMatcher.group(2);
                float size = switch (level.length()) {
                    case 1 -> H1_FONT_SIZE;
                    case 2 -> H2_FONT_SIZE;
                    default -> H3_FONT_SIZE;
                };
                Paragraph heading = new Paragraph()
                        .setFont(font)
                        .setFontSize(size)
                        .setMarginTop(15)
                        .setMarginBottom(8);
                for (Text t : parseInline(text, font)) {
                    heading.add(t);
                }
                document.add(heading);
                continue;
            }

            Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
            Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);

            if (ulMatcher.matches() || olMatcher.matches()) {
                String itemText = ulMatcher.matches() ? ulMatcher.group(1) : olMatcher.group(1);
                boolean isOrdered = olMatcher.matches();

                if (currentList == null || orderedList != isOrdered) {
                    if (currentList != null) document.add(currentList);
                    currentList = new List();
                    currentList.setMarginLeft(20);
                    currentList.setFontSize(BODY_FONT_SIZE);
                    if (!isOrdered) {
                        currentList.setListSymbol("•");
                    }
                    orderedList = isOrdered;
                }

                ListItem item = new ListItem();
                Paragraph itemPara = new Paragraph().setFont(font).setFontSize(BODY_FONT_SIZE);
                for (Text t : parseInline(itemText, font)) {
                    itemPara.add(t);
                }
                item.add(itemPara);
                currentList.add(item);
                continue;
            }

            // Regular paragraph
            if (currentList != null) {
                document.add(currentList);
                currentList = null;
            }

            Paragraph para = new Paragraph().setFont(font).setFontSize(BODY_FONT_SIZE).setMarginBottom(6);
            for (Text t : parseInline(line, font)) {
                para.add(t);
            }
            document.add(para);
        }

        if (currentList != null) {
            document.add(currentList);
        }
    }

    private java.util.List<Text> parseInline(String text, PdfFont font) {
        java.util.List<Text> result = new ArrayList<>();

        // Process bold segments
        Matcher bMatcher = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;

        while (bMatcher.find()) {
            if (bMatcher.start() > lastEnd) {
                result.add(new Text(text.substring(lastEnd, bMatcher.start())).setFont(font));
            }
            // Bold text: just use the same font (STSong doesn't have a bold variant)
            result.add(new Text(bMatcher.group(1)).setFont(font).setFontSize(BODY_FONT_SIZE));
            lastEnd = bMatcher.end();
        }

        if (lastEnd < text.length()) {
            result.add(new Text(text.substring(lastEnd)).setFont(font));
        }

        return result;
    }
}
