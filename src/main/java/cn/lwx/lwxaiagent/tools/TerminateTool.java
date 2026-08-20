package cn.lwx.lwxaiagent.tools;

import org.springframework.ai.tool.annotation.Tool;

/**
 * <h2>任务终止工具类</h2>
 * <p>
 * 提供一个"空操作"终止工具，让大语言模型（LLM）在判断任务已经完成（或无法继续）
 * 时，通过一次显式的函数调用来优雅地结束当前会话。
 * </p>
 *
 * <h3>核心设计目的</h3>
 * <p>
 * 在 LLM Agent 的多轮对话和工具编排中，模型有时无法自行判断"任务已完成"作为停止信号。
 * 通过暴露一个明确的 {@link #doTerminate()} 方法（被 {@link Tool @Tool} 注解标记），
 * LLM 可以在确认用户请求已被完全满足时调用此方法，表示本轮任务结束。
 * </p>
 *
 * <h3>调用时机</h3>
 * <p>LLM 会在以下情况下调用此方法：</p>
 * <ul>
 *   <li><b>任务完成</b>：用户的所有请求都已得到满足，无需进一步操作</li>
 *   <li><b>无法继续</b>：经过合理尝试后确定无法完成用户的请求（例如所需的工具不可用、外部服务不可达等）</li>
 *   <li><b>用户要求停止</b>：用户明确表示不需要继续或要求停止当前任务</li>
 * </ul>
 *
 * <h3>不应调用的时机</h3>
 * <p>如果仍有未完成的任务、需要继续处理的数据、或用户还有后续需求待满足，
 * LLM 不应该调用此方法。</p>
 *
 * <h3>返回值语义</h3>
 * <p>
 * 该方法返回一个简单的确认字符串 {@code "Terminating the program."}。
 * 这既向 LLM 确认了终止操作已被执行，也向应用程序的调用方（通常是 ChatClient 的调用方）
 * 表明 Agent 主动决定结束本轮会话。
 * </p>
 *
 * <h3>工具注册</h3>
 * <p>
 * 本类不标注 {@code @Component}，而是在
 * {@link cn.lwx.lwxaiagent.tools.ToolRegistration#allTools(org.springframework.ai.tool.ToolCallbackProvider, KnowledgeSearchTool)}
 * 中手动 {@code new} 实例化后注册。
 * </p>
 *
 * @author lwx-ai-agent
 * @see ToolRegistration
 */
public class TerminateTool {

    /**
     * <h3>终止当前任务</h3>
     * <p>
     * 这个方法的实现本身不做任何实际的系统操作——它只是一个"信号方法"。
     * 真正的终止逻辑由外部的 Agent 编排层（如 ChatClient 的循环控制逻辑）
     * 通过检测 LLM 是否调用了此方法来决定是否结束当前会话循环。
     * </p>
     *
     * <h4>工作原理</h4>
     * <ol>
     *   <li>LLM 在判断任务完成时，通过 Function Calling 调用此方法</li>
     *   <li>方法返回确认字符串 {@code "Terminating the program."}</li>
     *   <li>外部控制层检测到 {@code doTerminate} 被调用，结束 Agent 循环</li>
     * </ol>
     *
     * <h4>调用条件</h4>
     * <ul>
     *   <li>用户的请求已被完全满足</li>
     *   <li>经过合理尝试后无法继续推进</li>
     *   <li>用户明确要求停止</li>
     * </ul>
     *
     * <h4>注意事项</h4>
     * <ul>
     *   <li>只要还有未完成的工作，不要调用此方法</li>
     *   <li>调用后 LLM 不应再尝试执行任何后续操作</li>
     * </ul>
     *
     * @return 终止确认字符串，固定返回 {@code "Terminating the program."}
     */
    @Tool(description = "Terminate the current task. Call when the user's request has been fully satisfied, when you cannot proceed further after reasonable attempts, or when the user asks to stop. Do NOT call while there is still unfinished work.")
    public String doTerminate() {
        return "Terminating the program.";
    }
}
