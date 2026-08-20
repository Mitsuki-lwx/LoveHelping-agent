package cn.lwx.lwxaiagent.agent;

import lombok.Data;

/**
 * <h1>ReAct（Reasoning + Acting）Agent 抽象类</h1>
 *
 * <p>ReAct 是一种经典的 AI Agent 范式，将推理（Reasoning）和行动（Acting）交织在一起：</p>
 * <ol>
 *   <li><b>Think（思考）</b>：分析当前状态，决定下一步做什么</li>
 *   <li><b>Act（执行）</b>：根据思考结果，调用具体的工具或执行操作</li>
 *   <li><b>Observe（观察）</b>：接收工具执行结果，作为下一轮思考的输入</li>
 * </ol>
 *
 * <p><b>继承链：</b>{@code BaseAgent → ReActAgent → ToolCallAgent → LoveManus}</p>
 *
 * <p>每一层抽象增加的功能：</p>
 * <ul>
 *   <li>{@link BaseAgent}：核心属性（状态、消息列表、step 循环、SSE 流式输出）</li>
 *   <li>{@link ReActAgent}（本类）：引入 think/act 两步循环，定义 step() 为 think+act 的组合</li>
 *   <li>{@link ToolCallAgent}：具体实现 think（调用 LLM 判断是否需要工具）和 act（执行工具调用）</li>
 * </ul>
 *
 * <p><b>step() 方法的执行流程：</b></p>
 * <pre>
 *   step() {
 *       1. think() → 返回 boolean（是否需要执行动作）
 *       2. 如果需要 → act() → 返回工具执行结果
 *       3. 如果不需要 → 说明 LLM 已经给出了最终答案
 *   }
 * </pre>
 *
 * @see BaseAgent 父类——定义了 Agent 的基础属性和运行框架
 * @see ToolCallAgent 子类——实现了具体的 think 和 act 逻辑
 */
@Data
public abstract class ReActAgent extends BaseAgent {

    /**
     * 思考阶段：分析当前对话上下文，决定是否需要调用工具
     *
     * <p>这是 ReAct 范式的第一步，由子类（{@link ToolCallAgent}）具体实现。</p>
     *
     * <p><b>实现逻辑（在 ToolCallAgent 中）：</b></p>
     * <ol>
     *   <li>将消息列表发送给 LLM</li>
     *   <li>LLM 返回 AssistantMessage，其中可能包含 toolCalls</li>
     *   <li>如果有 toolCalls → 返回 true（需要执行 act）</li>
     *   <li>如果没有 toolCalls → 返回 false（LLM 直接回复了，无需工具）</li>
     * </ol>
     *
     * @return {@code true} = LLM 要求调用工具，接下来需要执行 act()；
     *         {@code false} = LLM 已完成回答，不需要进一步行动
     */
    public abstract boolean think();

    /**
     * 执行阶段：根据 think 阶段的结果，实际调用工具
     *
     * <p>这是 ReAct 范式的第二步，由子类（{@link ToolCallAgent}）具体实现。</p>
     *
     * <p><b>实现逻辑（在 ToolCallAgent 中）：</b></p>
     * <ol>
     *   <li>从 think 阶段的 ChatResponse 中提取 toolCalls</li>
     *   <li>通过 ToolCallingManager 执行每个被调用的工具</li>
     *   <li>将工具执行结果包装为 ToolResponseMessage 加入消息列表</li>
     *   <li>检查是否调用了 terminate 工具（如果是，标记 FINISHED）</li>
     * </ol>
     *
     * @return 工具执行结果的字符串表示（如 "toolsFileOperationToolresult: 文件已保存"）
     */
    public abstract String act();

    /**
     * 执行一步"思考→行动"循环
     *
     * <p>这是 {@link BaseAgent#runStream} 中每轮循环调用的入口。</p>
     *
     * <p><b>执行流程：</b></p>
     * <ol>
     *   <li>调用 {@link #think()}——让 LLM 决定是否需要用工具</li>
     *   <li>如果 think 返回 true → 调用 {@link #act()} 执行工具，返回结果字符串</li>
     *   <li>如果 think 返回 false → 说明 LLM 已给出最终答案，不需要更多动作</li>
     * </ol>
     *
     * <p><b>异常处理：</b>如果 think 或 act 过程中抛出异常，会被捕获并返回错误消息，
     * 不会中断整个 step 循环（给 Agent 一次自我纠正的机会）。</p>
     *
     * @return 当前 step 的结果描述——可能是工具执行结果，也可能是完成提示
     */
    @Override
    public String step() {
        try {
            // 第一步：思考——让 LLM 分析当前上下文并决定是否需要调用工具
            boolean shouldAct = think();
            if (shouldAct) {
                // 需要执行动作 → 调用工具，返回执行结果
                return act();
            } else {
                // 不需要动作 → LLM 已经给出了最终答案
                return " thinking completed, no action needed.";
            }
        } catch (Exception e) {
            // 打印异常堆栈到控制台（辅助调试）
            // TODO：应该改用 log.error 而不是 e.printStackTrace()
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }
}
