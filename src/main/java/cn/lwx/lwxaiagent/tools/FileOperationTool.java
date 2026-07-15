package cn.lwx.lwxaiagent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import static cn.lwx.lwxaiagent.constant.FileConstant.FILE_SAVE_DIR;
@Component
public class FileOperationTool {

    @Tool(name = "readFile", description = "Read the content of a text file (markdown, json, txt) from the application directory. Returns the full text content. Use this when you need to examine previously created files.")
    public String readFile(@ToolParam(description = "Name of the file to read") String fileName) {
        // 这里可以添加实际的文件读取逻辑
        String filePath = FILE_SAVE_DIR+"/" + fileName;
        try{
            return FileUtil.readUtf8String(filePath);
        }catch (Exception e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(name = "writeFile", description = "Write text content to a file in the application directory. Creates the file if it does not exist, overwrites if it does. Use this to save intermediate results or generated content.")
    public String writeFile(@ToolParam(description = "Name of the file to write") String fileName, @ToolParam(description = "Content to write") String content) {
        // 这里可以添加实际的文件写入逻辑
        String filePath = FILE_SAVE_DIR+"/" + fileName;
        try{
            FileUtil.writeUtf8String(content, filePath);
            return "File written successfully: " + fileName;
        }catch (Exception e) {
            return "Error writing file: " + e.getMessage();
        }

    }
}
