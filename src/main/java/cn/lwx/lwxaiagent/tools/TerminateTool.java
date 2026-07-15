package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
/**
 * @description: TerminateTool是一个工具类，用于结束程序的运行。
 *
 */
public class TerminateTool {
    @Tool(description = "Terminate the current task. Call when: ① all work is done (e.g. PDF generated); ② cannot proceed further after reasonable attempts; ③ user asks to stop. Do NOT call while there is still unfinished work.")
    public String doTerminate() {
        return "Terminating the program.";
    }
}
