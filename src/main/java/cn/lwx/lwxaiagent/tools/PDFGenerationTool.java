package cn.lwx.lwxaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.lwx.lwxaiagent.constant.FileConstant;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>PDF 生成工具类</h2>
 * <p>
 * 负责将 Markdown 格式的文本内容渲染为带样式的 PDF 文件，通过 Spring AI 的
 * {@link Tool @Tool} 注解将 PDF 生成能力暴露给大语言模型（LLM）进行函数调用。
 * </p>
 *
 * <h3>核心机制</h3>
 * <p>
 * 当用户明确要求生成 PDF 文件时，LLM 会自动调用
 * {@link #generatePDF(String, String, String)} 方法，将 Markdown 内容传入，
 * 由本类解析 Markdown 语法并使用 iText 7 库渲染为 PDF。
 * </p>
 *
 * <h3>支持的 Markdown 语法</h3>
 * <ul>
 *   <li><b>标题</b>：{@code # H1}、{@code ## H2}、{@code ### H3}（支持三级标题）</li>
 *   <li><b>加粗</b>：{@code **文本**}（使用 STSong 标准字体渲染，不支持真正的粗体变体）</li>
 *   <li><b>无序列表</b>：{@code - 项目} 或 {@code * 项目}</li>
 *   <li><b>有序列表</b>：{@code 1. 项目}、{@code 2. 项目} ...</li>
 *   <li><b>图片</b>：{@code ![](图片路径)} — 支持本地文件路径，自动去重（同一路径只渲染一次）</li>
 *   <li><b>水平分割线</b>：{@code ---}</li>
 * </ul>
 *
 * <h3>不支持的特性</h3>
 * <ul>
 *   <li>Emoji 表情（STSong 字体不支持，会在渲染前自动移除）</li>
 *   <li>Markdown 表格（竖线会被替换为空格以避免解析异常）</li>
 * </ul>
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>封面图片（{@code imagePath} 参数）仅支持一张</li>
 *   <li>正文中的图片通过 Markdown 的 {@code ![](path)} 语法嵌入，建议 4-6 张</li>
 *   <li>重复的图片路径会自动去重（通过 {@link HashSet} 记录已渲染的图片）</li>
 *   <li>PDF 使用中文 STSong（华文宋体）字体，确保中文字符正常显示</li>
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
 * @see FileConstant
 */
@Slf4j
public class PDFGenerationTool {

    /** 封面标题字体大小（24 号） */
    private static final float TITLE_FONT_SIZE = 24;
    /** 一级标题（H1）字体大小（18 号） */
    private static final float H1_FONT_SIZE = 18;
    /** 二级标题（H2）字体大小（15 号） */
    private static final float H2_FONT_SIZE = 15;
    /** 三级标题（H3）字体大小（13 号） */
    private static final float H3_FONT_SIZE = 13;
    /** 正文段落字体大小（11 号） */
    private static final float BODY_FONT_SIZE = 11;

    /** Markdown 标题匹配正则：匹配 1-3 个 # 号开头的行，捕获级别和标题文本 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,3})\\s+(.+)$");
    /** Markdown 加粗匹配正则：匹配 **文本** 语法，捕获加粗的文本内容 */
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    /** Markdown 无序列表匹配正则：匹配以 - 或 * 开头的列表项 */
    private static final Pattern UNORDERED_LIST_PATTERN = Pattern.compile("^[-*]\\s+(.+)$");
    /** Markdown 有序列表匹配正则：匹配以数字 + 点号开头的列表项 */
    private static final Pattern ORDERED_LIST_PATTERN = Pattern.compile("^\\d+\\.\\s+(.+)$");
    /** Markdown 水平分割线匹配正则：匹配 --- */
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^---\\s*$");
    /** Markdown 图片匹配正则：匹配 ![](path) 语法，捕获 alt 文本和图片路径 */
    private static final Pattern IMAGE_PATTERN = Pattern.compile("^!\\[(.*?)\\]\\((.*?)\\)$");

    /**
     * <h3>生成带样式的 PDF 文件</h3>
     * <p>
     * 将传入的 Markdown 内容解析并渲染为 PDF 文件，支持封面标题、封面图片和丰富的正文排版。
     * <b>仅在用户明确要求生成 PDF 时，LLM 才会调用此方法。</b>
     * </p>
     *
     * <h4>生成流程</h4>
     * <ol>
     *   <li><b>目录创建</b>：在文件保存目录下创建 {@code pdf/} 子目录</li>
     *   <li><b>内容清理</b>：调用 {@link #sanitizeContent(String)} 移除 emoji 和特殊字符</li>
     *   <li><b>封面渲染</b>：
     *     <ul>
     *       <li>提取第一个 H1 标题作为封面标题，使用深蓝色大字体居中显示</li>
     *       <li>添加当前日期</li>
     *       <li>整个封面区域使用浅米色背景（#F5F0EB）</li>
     *       <li>如有 {@code imagePath}，在封面下方插入封面图片（缩放适配 500x350）</li>
     *     </ul>
     *   </li>
     *   <li><b>正文渲染</b>：调用 {@link #renderMarkdown(Document, String, PdfFont, Set)} 逐行解析</li>
     *   <li><b>返回访问链接</b>：生成可点击的 HTTP 链接供用户预览或下载</li>
     * </ol>
     *
     * <h4>访问路径</h4>
     * <p>生成的 PDF 通过 {@code /api/files/pdf/{fileName}} 接口提供 HTTP 访问。
     * 文件名会进行 URL 编码以处理特殊字符。</p>
     *
     * @param fileName  PDF 文件名（不含路径），例如 "report.pdf"、"恋爱计划.pdf"
     * @param content   要渲染的 Markdown 格式文本内容。支持标题、列表、加粗、图片等语法
     * @param imagePath 封面图片的本地路径或 URL（可选参数，可为 null 或空字符串）。
     *                  注意：封面图仅支持一张；如需在正文中嵌入多张图片，
     *                  请在 Markdown 内容中使用 {@code ![](path)} 语法
     * @return 成功时返回包含 PDF 访问链接的字符串（带 emoji 图标）；
     *         失败时返回英文错误提示，建议用户移除不支持的字符后重试
     */
    @Tool(description = "Generate a styled PDF with markdown content. ONLY use when the user explicitly asks for a PDF file. For showing images, use markdown image syntax in your response instead. Supports: headings (# ## ###), bold (**text**), lists (- / 1.), images (![](local_path)). Rules: imagePath param supports one cover image only — embed multiple images (4-6) using ![](path) in the markdown body. Duplicate image paths are auto-deduplicated. Emoji and markdown tables are NOT supported (auto-removed). Use simple filenames without special characters. Image paths come from downloadImages output.")
    public String generatePDF(
            @ToolParam(description = "File name (e.g. plan.pdf)") String fileName,
            @ToolParam(description = "Content in markdown format") String content,
            @ToolParam(description = "Path or URL of an image to include at the top (optional)") String imagePath) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        // 安全（2026-09-06 工具抽查）：fileName 直拼可路径穿越——清洗路径成分
        fileName = cn.lwx.lwxaiagent.tools.ToolSafety.sanitizeFileName(fileName);
        if (fileName.isBlank()) {
            return "文件名非法";
        }
        String filePath = fileDir + "/" + fileName;
        try {
            // 确保 PDF 输出目录存在
            FileUtil.mkdir(fileDir);

            // 清理不支持的内容（emoji、表格竖线等）
            content = sanitizeContent(content);

            // 使用 try-with-resources 确保 PDF 资源正确释放
            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {

                // 使用 STSong（华文宋体）中文字体，支持简体中文渲染
                PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
                // 设置页边距为 50 点
                document.setMargins(50, 50, 50, 50);

                // 用于记录已渲染的图片 URL，避免重复
                Set<String> seenImages = new HashSet<>();

                // --- 封面标题区域 ---
                String title = extractTitle(content);
                if (title != null) {
                    // 封面区域：浅米色背景，内边距 30，顶部留白 100
                    Div coverDiv = new Div();
                    coverDiv.setBackgroundColor(new DeviceRgb(0xF5, 0xF0, 0xEB));
                    coverDiv.setPadding(30);
                    coverDiv.setMarginTop(100);
                    coverDiv.setMarginBottom(20);

                    // 封面标题：深蓝色，大号字体，居中显示
                    Paragraph coverTitle = new Paragraph(title)
                            .setFont(font)
                            .setFontSize(TITLE_FONT_SIZE)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setFontColor(new DeviceRgb(0x1A, 0x3C, 0x6E));
                    coverDiv.add(coverTitle);

                    // 当前日期：小字居中，放在标题下方
                    Paragraph coverDate = new Paragraph(java.time.LocalDate.now().toString())
                            .setFont(font)
                            .setFontSize(12)
                            .setTextAlignment(TextAlignment.CENTER)
                            .setMarginTop(10);
                    coverDiv.add(coverDate);
                    document.add(coverDiv);
                    document.add(new Paragraph("\n"));
                }

                // --- 封面图片 ---
                if (imagePath != null && !imagePath.isBlank()) {
                    // 安全（2026-09-06）：URL 型封面过协议/内网校验（本地相对路径放行）
                    if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
                        String why = cn.lwx.lwxaiagent.tools.ToolSafety.validateHttpUrl(imagePath);
                        if (why != null) {
                            return "封面图 URL 不合法: " + why;
                        }
                    }
                    seenImages.add(imagePath);  // 记录封面图片 URL，避免正文中重复
                    try {
                        Image img = new Image(ImageDataFactory.create(imagePath));
                        img.scaleToFit(500, 350);  // 等比缩放适配
                        document.add(img);
                        document.add(new Paragraph("\n"));
                    } catch (Exception e) {
                        // 封面图片加载失败不中断整个 PDF 生成，仅记录警告日志
                        log.warn("封面图片加载失败, path={}: {}", imagePath, e.getMessage());
                    }
                }

                // --- 正文渲染 ---
                renderMarkdown(document, content, font, seenImages);

            }
            // URL 编码文件名以处理中文和特殊字符，将 + 替换为 %20（空格的标准编码）
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
            // 构建 HTTP 访问路径供前端下载
            String urlPath = "/api/files/pdf/" + encodedFileName;
            return "✅ PDF 已生成！\n\n📄 [" + fileName + "](" + urlPath + ")\n\n点击上方链接可在浏览器中预览或下载。";
        } catch (Exception e) {
            log.warn("PDF generation failed: {}", e.getMessage());
            return "PDF generation failed. Please avoid unsupported characters (emoji, special symbols) in the content and try again.";
        }
    }

    /**
     * <h3>内容清理</h3>
     * <p>
     * 在 PDF 渲染前对 Markdown 内容进行预处理，移除不支持的字符：
     * </p>
     * <ul>
     *   <li><b>Emoji</b>：移除 Unicode 代理对（Surrogate Pairs）和各种 Emoji 码点范围，
     *       因为 STSong 字体不支持渲染 Emoji</li>
     *   <li><b>表格竖线</b>：将 {@code |} 替换为空格，因为 iText 的段落渲染器
     *       会把竖线误解为表格分隔符导致布局异常</li>
     * </ul>
     *
     * @param content 原始 Markdown 文本
     * @return 清理后的文本（emoji 被移除，竖线被替换为空格）
     */
    private String sanitizeContent(String content) {
        if (content == null) return null;
        // 移除 emoji（STSong 字体不支持），替换表格竖线
        return content
                .replaceAll("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]", "")   // 代理对（高位 + 低位）
                .replaceAll("[\\u2600-\\u27BF]", "")                     // 杂项符号
                .replaceAll("[\\u2300-\\u23FF]", "")                     // 杂项技术符号
                .replaceAll("[\\uFE00-\\uFE0F]", "")                     // 变体选择符
                .replaceAll("[\\u200D]", "")                             // 零宽连接符
                .replace('|', ' ')                                       // 表格竖线替换为空格
                .trim();
    }

    /**
     * <h3>提取封面标题</h3>
     * <p>
     * 从 Markdown 内容中匹配第一个一级标题（{@code # 标题}），作为 PDF 封面的主标题。
     * 使用多行模式（{@code Pattern.MULTILINE}）匹配，确保 {@code ^} 能匹配每行的开头。
     * </p>
     *
     * @param content Markdown 文本内容
     * @return 标题文本（去除 # 符号和前后空格）；如果没有找到 H1 标题则返回 {@code null}
     */
    private String extractTitle(String content) {
        Matcher m = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE).matcher(content);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * <h3>渲染 Markdown 正文内容到 PDF</h3>
     * <p>
     * 逐行解析 Markdown 文本，将不同的 Markdown 元素转换为对应的 iText PDF 元素。
     * 这是 PDF 生成的核心渲染引擎。
     * </p>
     *
     * <h4>支持的语法及渲染方式</h4>
     * <table>
     *   <tr><th>Markdown 语法</th><th>PDF 渲染效果</th></tr>
     *   <tr><td>空行</td><td>结束当前列表，开始新段落</td></tr>
     *   <tr><td>{@code ---}</td><td>浅灰色水平分割线</td></tr>
     *   <tr><td>{@code ![](path)}</td><td>嵌入图片（自动去重、URL 路径转换）</td></tr>
     *   <tr><td>{@code # H1}</td><td>深蓝色大字标题 + 底部蓝色分隔线</td></tr>
     *   <tr><td>{@code ## H2}</td><td>中等字号标题</td></tr>
     *   <tr><td>{@code ### H3}</td><td>稍小字号标题</td></tr>
     *   <tr><td>{@code - 项目} / {@code * 项目}</td><td>无序列表（• 符号）</td></tr>
     *   <tr><td>{@code 1. 项目}</td><td>有序列表（数字编号）</td></tr>
     *   <tr><td>普通段落</td><td>正文段落</td></tr>
     * </table>
     *
     * <h4>列表连续性处理</h4>
     * <p>
     * 使用 {@code currentList} 变量缓存当前正在构建的列表对象。当遇到新的列表项时，
     * 如果与当前列表类型相同则追加；如果类型不同（有序/无序切换）或遇到非列表行，
     * 则先将当前列表写入文档再开始新的列表，确保列表的连续性。
     * </p>
     *
     * <h4>图片去重</h4>
     * <p>
     * 通过 {@code seenImages} 集合记录已渲染的图片路径，避免同一张图片在 PDF 中重复出现。
     * 该集合同时记录了封面图片，因此封面图不会在正文中再次渲染。
     * </p>
     *
     * @param document   iText 的 Document 对象，PDF 文档的顶层容器
     * @param content    待渲染的 Markdown 文本（已清理 emoji 等特殊字符）
     * @param font       STSong 中文字体对象，用于所有文本渲染
     * @param seenImages 已渲染图片 URL 集合，用于去重
     */
    private void renderMarkdown(Document document, String content, PdfFont font, Set<String> seenImages) {
        // 按换行符拆分 Markdown 内容为逐行数组
        String[] lines = content.split("\n");
        // 当前正在构建的列表对象（null 表示不在列表中）
        List currentList = null;
        // 当前列表是否为有序列表
        boolean orderedList = false;
        // 标记是否遇到第一个 H1（已废弃，当前未使用）
        boolean isFirstH1 = true;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // --- 空行处理：结束当前列表 ---
            if (line.isEmpty()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                continue;
            }

            // --- 水平分割线处理 ---
            if (HORIZONTAL_RULE.matcher(line).matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                Div hr = new Div();
                hr.setHeight(1);                              // 线高 1 点
                hr.setBackgroundColor(ColorConstants.LIGHT_GRAY); // 浅灰色
                hr.setMarginTop(10);
                hr.setMarginBottom(10);
                document.add(hr);
                continue;
            }

            // --- 图片处理 ---
            Matcher imgMatcher = IMAGE_PATTERN.matcher(line);
            if (imgMatcher.matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                String imgPath = imgMatcher.group(2);  // 获取 Markdown 图片语法中的路径部分
                // 将 HTTP URL 解析回本地文件路径（PDF 渲染需要实际文件）
                if (imgPath.startsWith("/api/files/downloads/")) {
                    imgPath = imgPath.replace("/api/files/downloads/",
                            cn.lwx.lwxaiagent.constant.FileConstant.FILE_SAVE_DIR.replace("\\", "/") + "/downloads/");
                }
                // 图片去重：同一 URL 只渲染一次
                if (!seenImages.add(imgPath)) {
                    continue;
                }
                try {
                    Image img = new Image(ImageDataFactory.create(imgPath));
                    img.scaleToFit(500, 400);  // 等比缩放适配
                    document.add(img);
                    document.add(new Paragraph("\n"));
                } catch (Exception e) {
                    log.warn("PDF markdown 内嵌图片加载失败, path={}: {}", imgPath, e.getMessage());
                }
                continue;
            }

            // --- 标题处理（H1 / H2 / H3）---
            Matcher hMatcher = HEADING_PATTERN.matcher(line);
            if (hMatcher.matches()) {
                if (currentList != null) {
                    document.add(currentList);
                    currentList = null;
                }
                String level = hMatcher.group(1);    // # 号数量（1-3）
                String text = hMatcher.group(2);     // 标题文本
                // 根据 # 号数量选择字体大小
                float size = switch (level.length()) {
                    case 1 -> H1_FONT_SIZE;   // # → 18 号
                    case 2 -> H2_FONT_SIZE;   // ## → 15 号
                    default -> H3_FONT_SIZE;  // ### → 13 号
                };
                Paragraph heading = new Paragraph()
                        .setFont(font)
                        .setFontSize(size)
                        .setMarginTop(level.length() == 1 ? 25 : 15)
                        .setMarginBottom(8);
                // H1 标题使用深蓝色
                if (level.length() == 1) {
                    heading.setFontColor(new DeviceRgb(0x1A, 0x3C, 0x6E));
                }
                // 解析行内格式（加粗等）并添加到标题段落
                for (Text t : parseInline(text, font)) {
                    heading.add(t);
                }
                document.add(heading);
                // H1 底部加分隔线：深蓝色，高 2 点
                if (level.length() == 1) {
                    Div lineDiv = new Div();
                    lineDiv.setHeight(2f);
                    lineDiv.setBackgroundColor(new DeviceRgb(0x1A, 0x3C, 0x6E));
                    lineDiv.setMarginBottom(12);
                    document.add(lineDiv);
                }
                continue;
            }

            // --- 列表处理 ---
            Matcher ulMatcher = UNORDERED_LIST_PATTERN.matcher(line);
            Matcher olMatcher = ORDERED_LIST_PATTERN.matcher(line);

            if (ulMatcher.matches() || olMatcher.matches()) {
                String itemText = ulMatcher.matches() ? ulMatcher.group(1) : olMatcher.group(1);
                boolean isOrdered = olMatcher.matches();

                // 如果当前没有列表，或列表类型发生变化（有序 ↔ 无序），则创建新列表
                if (currentList == null || orderedList != isOrdered) {
                    if (currentList != null) document.add(currentList);  // 先输出旧列表
                    currentList = new List();
                    currentList.setMarginLeft(20);
                    currentList.setFontSize(BODY_FONT_SIZE);
                    if (!isOrdered) {
                        currentList.setListSymbol("•");  // 无序列表使用圆点符号
                    }
                    orderedList = isOrdered;
                }

                // 创建列表项，支持行内格式（加粗等）
                ListItem item = new ListItem();
                Paragraph itemPara = new Paragraph().setFont(font).setFontSize(BODY_FONT_SIZE);
                for (Text t : parseInline(itemText, font)) {
                    itemPara.add(t);
                }
                item.add(itemPara);
                currentList.add(item);
                continue;
            }

            // --- 普通段落处理 ---
            if (currentList != null) {
                document.add(currentList);  // 先结束之前的列表
                currentList = null;
            }

            // 创建普通段落，支持行内加粗格式
            Paragraph para = new Paragraph().setFont(font).setFontSize(BODY_FONT_SIZE).setMarginBottom(6);
            for (Text t : parseInline(line, font)) {
                para.add(t);
            }
            document.add(para);
        }

        // 文档末尾：如果还有未关闭的列表，写入文档
        if (currentList != null) {
            document.add(currentList);
        }
    }

    /**
     * <h3>解析行内格式</h3>
     * <p>
     * 解析一行文本中的 Markdown 行内格式（目前仅支持加粗语法 {@code **文本**}），
     * 将普通文本和加粗文本分别创建为 iText 的 {@link Text} 对象，返回按顺序排列的文本片段列表。
     * </p>
     *
     * <h4>为什么加粗不是真正的 Bold</h4>
     * <p>
     * STSong（华文宋体）标准字体不包含 Bold 变体，因此即使检测到加粗标记，
     * 也只能使用标准字体渲染。这里保留了加粗的解析逻辑，
     * 以便将来更换支持粗体变体的字体时能直接生效。
     * </p>
     *
     * <h4>解析逻辑</h4>
     * <ol>
     *   <li>使用 {@link #BOLD_PATTERN} 正则匹配所有 {@code **文本**} 加粗段</li>
     *   <li>加粗段之前的普通文本 → 创建普通 Text</li>
     *   <li>加粗文本 → 创建 Text（字体大小设为 {@link #BODY_FONT_SIZE}）</li>
     *   <li>最后一个加粗段之后的剩余文本 → 创建普通 Text</li>
     * </ol>
     *
     * @param text 单行文本，可能包含 {@code **文本**} 加粗标记
     * @param font STSong 中文字体对象
     * @return 按原文顺序排列的 iText Text 片段列表，用于段落或列表项的构建
     */
    private java.util.List<Text> parseInline(String text, PdfFont font) {
        java.util.List<Text> result = new ArrayList<>();

        // 使用正则匹配所有加粗段
        Matcher bMatcher = BOLD_PATTERN.matcher(text);
        int lastEnd = 0;  // 上一个加粗段结束的位置

        while (bMatcher.find()) {
            // 加粗段之前如果有普通文本，先添加到结果中
            if (bMatcher.start() > lastEnd) {
                result.add(new Text(text.substring(lastEnd, bMatcher.start())).setFont(font));
            }
            // 加粗文本：使用标准字体（STSong 没有粗体变体，此处预留接口）
            result.add(new Text(bMatcher.group(1)).setFont(font).setFontSize(BODY_FONT_SIZE));
            lastEnd = bMatcher.end();
        }

        // 最后一个加粗段之后的剩余文本
        if (lastEnd < text.length()) {
            result.add(new Text(text.substring(lastEnd)).setFont(font));
        }

        return result;
    }
}
