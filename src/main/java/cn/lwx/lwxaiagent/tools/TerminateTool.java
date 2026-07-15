package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;
/**
 * @description: TerminateTool是一个工具类，用于结束程序的运行。
 *
 */
public class TerminateTool {
    @Tool(description = """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            "When you have finished all the tasks, call this tool to end the work.
            """)
    public String doTerminate() {
        return "Terminating the program.";
    }
}
