package cn.lwx.lwxaiagent.memory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * <h1>记忆系统配置属性 —— 控制对话记忆窗口大小和存储策略</h1>
 *
 * <p><strong>核心作用：</strong>作为 Spring Boot 的配置属性类，将 {@code application.yml}
 * 中以 {@code app.memory} 为前缀的配置项自动绑定到此类的对应字段上。</p>
 *
 * <h2>配置前缀</h2>
 * <p>所有属性在 YAML 中的前缀为 <b>{@code app.memory}</b>，例如：</p>
 * <pre>
 * app:
 *   memory:
 *     window-size: 20
 *     agent-window-size: 50
 *     strategy: JDBC
 * </pre>
 *
 * <h2>配置项说明</h2>
 *
 * <h3>普通对话窗口大小（{@code windowSize}，默认 20）</h3>
 * <p>用于一般的情感咨询对话。20 条消息约等于 10 轮对话（每轮包含 1 条用户消息 + 1 条 AI 回复）。
 * 超过窗口大小的历史消息将在新消息加入时被自动移出上下文。</p>
 * <p><strong>选择 20 的理由：</strong></p>
 * <ul>
 *   <li>多数情感咨询对话在 10 轮内已经能充分展开话题</li>
 *   <li>控制 Token 消耗，避免上下文过长导致成本居高不下</li>
 *   <li>过长的上下文可能引入噪音，降低 LLM 回复的相关性</li>
 * </ul>
 *
 * <h3>Agent 对话窗口大小（{@code agentWindowSize}，默认 50）</h3>
 * <p>用于 Agent 场景（如 LoveManus 多步骤推理代理）。Agent 需要更大的上下文窗口，
 * 因为每次工具调用和内部推理都会消耗消息配额。</p>
 * <p><strong>选择 50 的理由：</strong></p>
 * <ul>
 *   <li>Agent 的每次决策链可能包含 3-5 步，消耗 6-10 条消息</li>
 *   <li>需要保留足够的历史上下文让 Agent 做一致性决策</li>
 *   <li>50 条消息在大多数 LLM 的上下文窗口（如 DeepSeek 的 128K tokens）内仅占一小部分</li>
 * </ul>
 *
 * <h3>记忆策略（{@code strategy}，默认 "JDBC"）</h3>
 * <p>指定底层存储方案。当前仅支持 JDBC（MySQL），但预留了 REDIS、FILE 等扩展可能。
 * 策略名称对应不同的 {@link org.springframework.ai.chat.memory.ChatMemory} 实现类。</p>
 *
 * <h2>窗口大小与 Token 消耗的关系</h2>
 * <p>虽然窗口大小以"条消息"为单位，但实际影响的是 Token 消耗。每条消息的 Token 数差异很大：</p>
 * <ul>
 *   <li>简短消息：50-100 tokens / 条</li>
 *   <li>详细咨询：200-500 tokens / 条</li>
 *   <li>Agent 工具调用：100-300 tokens / 条</li>
 * </ul>
 * <p>以平均 200 tokens/条计算，20 条消息约 4,000 tokens（约占 DeepSeek 128K 上下文的 3%），
 * 50 条消息约 10,000 tokens（约占 8%），都在安全范围内。</p>
 *
 * <p>使用 Lombok {@code @Data} 注解自动生成所有字段的 getter 和 setter 方法。</p>
 *
 * @see cn.lwx.lwxaiagent.memory.ChatMemoryFactory 聊天记忆工厂 —— 读取这些配置创建 ChatMemory 实例
 * @see cn.lwx.lwxaiagent.memory.MemoryService 记忆管理服务 —— 提供记忆的查询和清理功能
 */
@Data
@ConfigurationProperties(prefix = "app.memory")
public class MemoryProperties {

    /**
     * <h3>普通对话记忆窗口大小（条消息）</h3>
     *
     * <p>定义普通对话场景（如情感咨询）中保留的最大消息数。这是一个滑动窗口 ——
     * 当消息数量超过此值时，最早的消息会被自动移除。</p>
     *
     * <p><b>默认值：</b>20</p>
     * <p><b>YAML 配置：</b>{@code app.memory.window-size}</p>
     */
    private int windowSize = 20;

    /**
     * <h3>Agent 对话记忆窗口大小（条消息）</h3>
     *
     * <p>定义 Agent 场景（如 LoveManus 多步骤推理代理）中保留的最大消息数。
     * Agent 需要比普通对话更大的窗口，因为每次推理步骤（工具调用、中间结果）
     * 都会消耗消息配额。</p>
     *
     * <p><b>默认值：</b>50</p>
     * <p><b>YAML 配置：</b>{@code app.memory.agent-window-size}</p>
     */
    private int agentWindowSize = 50;

    /**
     * <h3>记忆存储策略</h3>
     *
     * <p>指定对话记忆的底层存储方案。当前支持的策略：</p>
     * <ul>
     *   <li><b>JDBC：</b>数据存储在 MySQL 的 {@code SPRING_AI_CHAT_MEMORY} 表中，
     *       由 Spring AI 的 {@code JdbcChatMemoryRepository} 实现</li>
     * </ul>
     *
     * <p><b>预留扩展策略（暂未实现）：</b></p>
     * <ul>
     *   <li><b>REDIS：</b>使用 Redis 存储，适合需要更高读写性能的场景</li>
     *   <li><b>FILE：</b>使用本地文件存储，适合开发测试环境</li>
     * </ul>
     *
     * <p><b>默认值：</b>"JDBC"</p>
     * <p><b>YAML 配置：</b>{@code app.memory.strategy}</p>
     */
    private String strategy = "JDBC";
}
