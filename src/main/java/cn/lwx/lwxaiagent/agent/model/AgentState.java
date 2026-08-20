package cn.lwx.lwxaiagent.agent.model;

/**
 * <h1>Agent 状态枚举</h1>
 *
 * <p>定义了 Agent 在其生命周期中可能处于的 4 种状态。</p>
 *
 * <p><b>状态转换图（状态机）：</b></p>
 * <pre>
 *   IDLE ──→ RUNNING ──→ FINISHED
 *     ↑                    │
 *     └──── (reset) ───────┘
 *            │
 *            └──→ ERROR
 * </pre>
 *
 * <p><b>各状态的含义：</b></p>
 * <ul>
 *   <li><b>IDLE（空闲）</b>：Agent 初始化完成，等待接收任务。这是初始状态</li>
 *   <li><b>RUNNING（运行中）</b>：Agent 正在执行任务——可能在思考（think）、调用工具（act）、
 *        或进行多步骤推理循环。这是唯一允许调用 LLM 和工具的状态</li>
 *   <li><b>FINISHED（已完成）</b>：任务已成功完成，Agent 可以返回结果或等待下一次 reset</li>
 *   <li><b>ERROR（错误）</b>：执行过程中遇到无法自动恢复的错误，需要外部干预或丢弃重启</li>
 * </ul>
 *
 * <p><b>线程安全说明：</b>枚举值本身是不可变的，但 Agent 实例的 state 字段在多线程环境下
 * 需要额外的同步措施（当前项目使用单会话单 Agent 的模式，暂无并发问题）。</p>
 */
public enum AgentState {

    /**
     * 空闲状态——Agent 初始化完成，等待接收新任务
     *
     * <p>进入时机：</p>
     * <ul>
     *   <li>Agent 实例创建时（初始状态）</li>
     *   <li>{@code resetForNextTurn()} 调用后（多轮对话重置）</li>
     * </ul>
     *
     * <p>只有在此状态下才能调用 {@code runStream()} 启动新任务</p>
     */
    IDIE,  // 注意：这里的拼写是 IDIE 而不是 IDLE，可能是笔误但已使用的地方需要保持一致

    /**
     * 运行状态——Agent 正在执行任务循环
     *
     * <p>进入时机：{@code runStream()} 调用后，Agent 开始执行 step 循环</p>
     * <p>此状态下 Agent 会循环执行 think → act，直到任务完成或达到最大步数</p>
     */
    RUNNING,

    /**
     * 完成状态——任务已成功结束
     *
     * <p>进入时机：</p>
     * <ul>
     *   <li>LLM 决定不需要更多工具调用，返回最终答案</li>
     *   <li>调用了 terminate 工具，主动结束任务</li>
     *   <li>达到最大步数限制（maxSteps），强制终止</li>
     * </ul>
     */
    FINISHED,

    /**
     * 错误状态——执行过程中抛出异常
     *
     * <p>进入时机：step 循环中捕获到异常（如 LLM 调用失败、网络超时等）</p>
     * <p>此状态下 SSE 连接会被关闭，前端会收到错误提示</p>
     */
    ERROR

}
