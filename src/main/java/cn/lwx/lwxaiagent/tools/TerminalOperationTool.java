package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

public class TerminalOperationTool {

    @Tool(description = "Execute a command in the terminal")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder;
            String os = System.getProperty("os.name").toLowerCase();
            String charsetName = "UTF-8"; // 默认 UTF-8

            if (os.contains("win")) {
                // Windows 使用 cmd，编码为 GBK
                builder = new ProcessBuilder("cmd.exe", "/c", command);
                charsetName = "GBK";
            } else {
                // Linux/Mac 使用 bash
                builder = new ProcessBuilder("bash", "-c", command);
                charsetName = "UTF-8";
            }

            Process process = builder.start();

            // 使用正确的编码读取输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charsetName))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // 读取错误流
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), charsetName))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        output.append("[ERROR] ").append(line).append("\n");
                    }
                }
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.toString();
    }
}