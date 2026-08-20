package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * <h2>终端命令执行工具类</h2>
 * <p>
 * 提供在服务器端执行操作系统命令的能力，通过 Spring AI 的 {@link Tool @Tool} 注解
 * 将终端操作能力暴露给大语言模型（LLM）进行函数调用（Function Calling）。
 * </p>
 *
 * <h3>核心机制</h3>
 * <p>
 * 当 LLM 需要执行系统命令或脚本时（例如文件管理、进程检查、调用外部工具等），
 * 会自动调用 {@link #executeTerminalCommand(String)} 方法，
 * 在服务器上以子进程方式运行指定命令，并将命令的标准输出和错误输出作为结果返回。
 * </p>
 *
 * <h3>跨平台支持</h3>
 * <p>
 * 本工具根据操作系统类型自动选择不同的 shell 环境：
 * </p>
 * <ul>
 *   <li><b>Windows</b>：使用 {@code cmd.exe /c <command>} 执行命令，输出编码为 <b>GBK</b>
 *       （Windows 中文系统的默认控制台编码）</li>
 *   <li><b>Linux / Mac</b>：使用 {@code bash -c <command>} 执行命令，输出编码为 <b>UTF-8</b></li>
 * </ul>
 *
 * <h3>安全注意事项</h3>
 * <p>
 * 命令以应用程序的系统权限执行，没有任何命令过滤或沙箱机制。
 * <b>这意味着 LLM 理论上可以执行任何系统命令</b>，包括危险操作
 * （如删除文件、修改系统配置等）。生产环境部署时建议：
 * </p>
 * <ul>
 *   <li>以受限用户身份运行应用程序</li>
 *   <li>考虑对允许执行的命令实行白名单机制</li>
 *   <li>在容器（Docker）等隔离环境中运行</li>
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
 */
public class TerminalOperationTool {

    /**
     * <h3>执行终端命令</h3>
     * <p>
     * 在服务器操作系统的 shell 环境中执行指定的命令，捕获标准输出和标准错误输出，
     * 并将结果作为字符串返回给 LLM。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>操作系统识别</b>：通过 {@code os.name} 系统属性判断当前操作系统</li>
     *   <li><b>Shell 选择</b>：
     *     <ul>
     *       <li>Windows → {@code cmd.exe /c <command>}</li>
     *       <li>其他 → {@code bash -c <command>}</li>
     *     </ul>
     *   </li>
     *   <li><b>编码设置</b>：
     *     <ul>
     *       <li>Windows → GBK（Windows 中文控制台默认编码）</li>
     *       <li>其他 → UTF-8</li>
     *     </ul>
     *   </li>
     *   <li><b>启动子进程</b>：通过 {@link ProcessBuilder} 创建并启动外部进程</li>
     *   <li><b>标准输出读取</b>：使用正确的字符编码读取命令的标准输出流</li>
     *   <li><b>退出码检查</b>：等待进程结束，如果退出码不为 0，额外读取标准错误流，
     *       并将错误信息附加到输出结果中</li>
     *   <li><b>异常处理</b>：捕获 IO 异常和中断异常，返回友好的错误描述</li>
     * </ol>
     *
     * <h4>结果格式</h4>
     * <ul>
     *   <li><b>正常执行</b>：返回标准输出的内容</li>
     *   <li><b>执行失败（退出码 != 0）</b>：返回标准输出 + 标准错误（每行以 [ERROR] 前缀标记）
     *       + "Command execution failed with exit code: N"</li>
     *   <li><b>进程异常</b>：返回 "Error executing command: " + 异常消息</li>
     * </ul>
     *
     * @param command 要在终端中执行的命令字符串。对于 Windows，这是 cmd.exe 的命令；
     *                对于 Linux/Mac，这是 bash 命令。LLM 可以将多个命令串联执行
     *                （例如使用 {@code &&} 连接符）
     * @return 命令执行的标准输出文本。如果执行失败，还会包含错误信息。
     *         返回的是纯文本字符串，可能包含换行符
     */
    @Tool(description = "Execute a terminal command (cmd on Windows, bash on Linux/Mac). Returns command output as text. Use this only when you need to run system commands or scripts. Security: commands run with the application's system permissions.")
    public String executeTerminalCommand(@ToolParam(description = "Command to execute in the terminal") String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder;
            // 通过 os.name 系统属性识别操作系统类型
            String os = System.getProperty("os.name").toLowerCase();
            // 默认编码为 UTF-8（Linux/Mac 使用）
            String charsetName = "UTF-8";

            if (os.contains("win")) {
                // Windows 使用 cmd.exe，编码为 GBK
                // Windows 中文系统的控制台默认编码是 GBK，不是 UTF-8
                builder = new ProcessBuilder("cmd.exe", "/c", command);
                charsetName = "GBK";
            } else {
                // Linux/Mac 使用 bash，编码为 UTF-8
                builder = new ProcessBuilder("bash", "-c", command);
                charsetName = "UTF-8";
            }

            // 启动子进程执行命令
            Process process = builder.start();

            // 使用正确的编码读取标准输出流
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charsetName))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            // 等待进程结束并获取退出码
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                // 退出码非 0 表示命令执行失败，读取错误流
                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), charsetName))) {
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        // 以 [ERROR] 前缀标记错误输出的每一行
                        output.append("[ERROR] ").append(line).append("\n");
                    }
                }
                // 附加退出码信息
                output.append("Command execution failed with exit code: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            // IO 异常（命令无法执行、进程 I/O 错误）
            // 中断异常（等待进程结束时线程被中断）
            output.append("Error executing command: ").append(e.getMessage());
        }
        return output.toString();
    }
}