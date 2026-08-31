package cn.lwx.lwxaiagent.infrastructure.orchestration.tools;

/**
 * 工具能力域（ADR-19 能力治理）。
 * <p>每个工具按其副作用面打一个域标签；节点按"域白名单"注入工具。
 * 未映射到任何域的工具 = 不识别 → 默认排除（安全默认：新工具不会自动暴露给 agent）。</p>
 */
public final class ToolCapability {

    private ToolCapability() {}

    /** 收尾（无副作用，终止循环用） */
    public static final String TERMINATE = "terminate";
    /** 知识库检索 */
    public static final String RETRIEVAL = "retrieval";
    /** 网络搜索 / 抓取 */
    public static final String WEB = "web";
    /** 天气查询 */
    public static final String WEATHER = "weather";
    /** 日期/规划 */
    public static final String DATE = "date";
    /** 图片搜索/下载（仅取图） */
    public static final String IMAGE = "image";
    /** 生成 PDF（写文件，副作用面） */
    public static final String PDF = "pdf";
    /** 下载网络资源（写本地，副作用面） */
    public static final String DOWNLOAD = "download";
}