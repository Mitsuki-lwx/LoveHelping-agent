package cn.lwx.lwxaiagent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static cn.lwx.lwxaiagent.constant.FileConstant.FILE_SAVE_DIR;

/**
 * <h2>文件操作工具类</h2>
 * <p>
 * 提供文件读写功能的工具类，通过 Spring AI 的 {@link Tool @Tool} 注解将方法暴露给
 * 大语言模型（LLM）进行函数调用（Function Calling）。
 * </p>
 *
 * <h3>核心机制</h3>
 * <p>
 * 使用 {@code @Component} 注解将其注册为 Spring 容器管理的 Bean，
 * 然后在 {@link cn.lwx.lwxaiagent.tools.ToolRegistration} 中通过
 * {@link org.springframework.ai.support.ToolCallbacks#from(Object...)} 方法
 * 包装为 {@link org.springframework.ai.tool.ToolCallback} 数组，最终注入到
 * {@link cn.lwx.lwxaiagent.infrastructure.ai.LoveApp} 的 ChatClient 中。
 * 当 LLM 判断需要读写文件时，会自动调用对应的方法，并将返回值作为上下文继续推理。
 * </p>
 *
 * <h3>暴露给 LLM 的方法</h3>
 * <ul>
 *   <li>{@link #readFile(String)} — 读取文本文件内容</li>
 *   <li>{@link #writeFile(String, String)} — 写入文本文件内容</li>
 * </ul>
 *
 * <h3>工作目录</h3>
 * <p>
 * 所有文件操作都限定在应用的文件保存目录（由 {@code FILE_SAVE_DIR} 常量指定）下，
 * 不允许访问系统其他路径，以保证安全性。
 * </p>
 *
 * @author lwx-ai-agent
 * @see ToolRegistration
 * @see cn.lwx.lwxaiagent.infrastructure.ai.LoveApp
 */
@Component
public class FileOperationTool {

    /**
     * <h3>读取文件内容</h3>
     * <p>
     * 从应用文件保存目录中读取指定文本文件（支持 markdown、json、txt 等格式）的全部内容。
     * LLM 在需要检查之前创建的文件内容时，会自动调用此方法。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>根据 {@code FILE_SAVE_DIR} 常量和传入的文件名拼接出完整的文件路径</li>
     *   <li>调用 Hutool 的 {@link FileUtil#readUtf8String(String)} 读取文件内容</li>
     *   <li>如果文件不存在或读取失败，返回错误信息字符串</li>
     * </ol>
     *
     * <h4>安全说明</h4>
     * <p>文件路径由应用内部拼接，用户/LLM 只能指定文件名，无法通过路径遍历访问系统其他目录。</p>
     *
     * @param fileName 要读取的文件名（仅文件名，不含路径），例如 "report.txt"、"data.json"
     * @return 文件的完整文本内容；如果读取失败，返回以 "Error reading file:" 开头的错误描述字符串
     */
    @Tool(name = "readFile", description = "Read the content of a text file (markdown, json, txt) from the application directory. Returns the full text content. Use this when you need to examine previously created files.")
    public String readFile(@ToolParam(description = "Name of the file to read") String fileName) {
        // 拼接完整的文件存储路径：基础目录 + "/" + 文件名
        String filePath = FILE_SAVE_DIR+"/" + fileName;
        try{
            // 使用 Hutool 工具类以 UTF-8 编码读取文件全部内容
            return FileUtil.readUtf8String(filePath);
        }catch (Exception e) {
            // 捕获所有异常（文件不存在、权限不足等），返回友好的错误提示
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * <h3>写入文件内容</h3>
     * <p>
     * 将文本内容写入应用文件保存目录下的指定文件中。如果文件已存在则覆盖，不存在则新建。
     * LLM 在需要保存中间结果、生成的报告或任何文本内容时，会自动调用此方法。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>根据 {@code FILE_SAVE_DIR} 常量和传入的文件名拼接出完整的文件路径</li>
     *   <li>调用 Hutool 的 {@link FileUtil#writeUtf8String(String, String)} 写入内容</li>
     *   <li>写入成功返回确认信息，失败返回错误描述</li>
     * </ol>
     *
     * <h4>使用场景</h4>
     * <p>LLM 可以利用此方法将生成的计划、报告、分析结果等内容持久化保存，后续可以通过
     * {@link #readFile(String)} 方法重新读取。</p>
     *
     * @param fileName 要写入的文件名（仅文件名，不含路径），例如 "plan.md"、"result.txt"
     * @param content  要写入文件的文本内容，可以是纯文本、Markdown、JSON 等任意格式的字符串
     * @return 写入成功返回 "File written successfully: " + 文件名；
     *         写入失败返回以 "Error writing file:" 开头的错误描述字符串
     */
    @Tool(name = "writeFile", description = "Write text content to a file in the application directory. Creates the file if it does not exist, overwrites if it does. Use this to save intermediate results or generated content.")
    public String writeFile(@ToolParam(description = "Name of the file to write") String fileName, @ToolParam(description = "Content to write") String content) {
        // 拼接完整的文件存储路径
        String filePath = FILE_SAVE_DIR+"/" + fileName;
        try{
            // 使用 Hutool 工具类以 UTF-8 编码将字符串内容写入文件
            // 如果文件不存在会自动创建，存在则覆盖
            FileUtil.writeUtf8String(content, filePath);
            return "File written successfully: " + fileName;
        }catch (Exception e) {
            // 捕获写入过程中的所有异常（磁盘空间不足、权限不足等）
            return "Error writing file: " + e.getMessage();
        }

    }
}
