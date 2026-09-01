package cn.lwx.lwxaiagent.infrastructure.orchestration.graph;

/**
 * 业务编排图（ADR-19）的全局状态键。
 * <p>节点通过 {@code Map<String,Object>} 读写这些键；图执行时增量合并。</p>
 */
public final class GraphStateKeys {

    private GraphStateKeys() {}

    /** 用户本条消息文本 */
    public static final String MESSAGE = "message";
    /** 会话 ID（message 表归属 / checkpoint threadId） */
    public static final String CHAT_ID = "chatId";
    /** 用户 ID */
    public static final String USER_ID = "userId";
    /** 媒体附件 ID 列表（视觉，暂保持透传） */
    public static final String MEDIA_IDS = "mediaIds";
    /** 路由类型：SIMPLE / NORMAL / SANDBOX / AGENT */
    public static final String ROUTE = "route";
    /** 是否为话术三级请求（FR-CORE-01） */
    public static final String ADVICE = "advice";
    /** 沙盘会话 ID（路由到沙盘节点时非空） */
    public static final String SANDBOX_ID = "sandboxId";
    /** 沙盘组装好的人格 prompt（沙盘节点产出，覆盖系统提示词） */
    public static final String SANDBOX_PROMPT = "sandboxPrompt";
    /** 已注入的上下文（记忆 + 知识库检索） */
    public static final String CONTEXT = "context";
    /** 工具循环内的消息列表（List&lt;org.springframework.ai.chat.messages.Message&gt;） */
    public static final String MESSAGES = "messages";
    /** 最终回复文本（检查/落库用） */
    public static final String OUTPUT = "output";
    /** 话术三级结构化 tiers（String，JSON，SSE advice 事件用） */
    public static final String ADVICE_TIERS = "adviceTiers";
    /** 强制走工具循环（LoveManus 通道，forceAgent=true 时置位） */
    public static final String FORCE_AGENT = "forceAgent";
    /** 本轮执行过的工具名（List&lt;String&gt;，SSE 🔧 可视化用） */
    public static final String TOOL_EVENTS = "toolEvents";
    /** 图路径 trace（List&lt;String&gt; 节点名序列，CAP-7 可观测） */
    public static final String GRAPH_PATH = "graph.path";

    /** 管道 trace 上下文透传：HTTP 入口 span 的 traceId/spanId（字符串；异步线程内恢复 OTLP 链路用，仅内存传递不落 checkpoint） */
    public static final String PIPELINE_TRACE_ID = "pipeline.traceId";
    public static final String PIPELINE_SPAN_ID = "pipeline.spanId";

    /** 路由常量 */
    public static final String ROUTE_SIMPLE = "SIMPLE";
    public static final String ROUTE_NORMAL = "NORMAL";
    public static final String ROUTE_SANDBOX = "SANDBOX";
    public static final String ROUTE_AGENT = "AGENT";
}