package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

/** 业务编排图（ADR-19）节点名常量。 */
public final class GraphNodes {

    private GraphNodes() {}

    /** 入口（无节点动作，仅起点） */
    public static final String START = "__start__";
    /** 简单问题判定（条件边 → QUICK_ANSWER / ROUTE） */
    public static final String CLASSIFY = "classify";
    /** 类型路由（条件边 → QUICK_ANSWER? no：SANDOX/NORMAL/AGENT 由本次判定决定） */
    public static final String ROUTE = "route";
    /** 简单问题直接回答 */
    public static final String QUICK_ANSWER = "quick_answer";
    /** 域外话题拒绝（R_OFF_TOPIC → 固定引导，不调 LLM） */
    public static final String OFF_TOPIC = "off_topic";
    /** 普通对话（记忆 + 知识库上下文 + 话术三级） */
    public static final String NORMAL = "normal";
    /** 沙盘对话（人设 + 沙盘记忆 + 动态情绪） */
    public static final String SANDBOX = "sandbox";
    /** 视觉对话（mediaIds 带图，ADR-11） */
    public static final String VISION = "vision";
    /** Agent 工具循环：LLM 生成（可带工具调用） */
    public static final String AGENT_LLM = "agent_llm";
    /** Agent 工具循环：执行工具并回填 */
    public static final String AGENT_TOOL = "agent_tool";
    /** 统一检查节点（护栏复检/话术激活标记/质量骨架） */
    public static final String CHECK = "check";
    /** 出口（无节点动作） */
    public static final String END = "__end__";
}