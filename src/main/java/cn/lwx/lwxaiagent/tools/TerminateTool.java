package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
/**
 * @description: TerminateTool is a tool class for ending program execution.
 *
 */
public class TerminateTool {
    @Tool(description = "Terminate the current task. Call when the user's request has been fully satisfied, when you cannot proceed further after reasonable attempts, or when the user asks to stop. Do NOT call while there is still unfinished work.")
    public String doTerminate() {
        return "Terminating the program.";
    }
}
