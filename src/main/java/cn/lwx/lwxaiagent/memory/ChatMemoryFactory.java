package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.admin.PromptVersionService;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.memory.config.MemoryProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <h1>聊天记忆统一工厂 —— 创建对话记忆实例的集中入口</h1>
 *
 * <p><strong>核心作用：</strong>作为创建 {@link ChatMemory}（对话记忆）实例的唯一工厂，
 * 统一管理所有场景下的对话记忆窗口大小配置，消除之前分散在各个 Service 中的重复和
 * 不一致的配置。</p>
 *
 * <h2>存储实现（Phase 2 变更）</h2>
 * <p>统一使用 {@link MessageChatMemory}（读写 {@code message} 表，见 ADR 消息落库）：
 * 内存无状态，每次读最近 N 条（窗口）注入上下文，写逐条 append 保留全量历史。
 * 取代旧的 {@code JdbcChatMemoryRepository + MessageWindowChatMemory}
 * （依赖 {@code SPRING_AI_CHAT_MEMORY} 表，只存窗口、无业务字段）。</p>
 * <ul>
 *   <li><b>普通对话：</b>使用 {@link #create()} —— 窗口大小由 {@code app.memory.window-size} 配置（默认 20）</li>
 *   <li><b>Agent 对话：</b>使用 {@link #createForAgent()} —— 窗口大小由 {@code app.memory.agent-window-size} 配置（默认 50）</li>
 *   <li><b>自定义窗口：</b>使用 {@link #create(int)} —— 直接指定窗口大小，用于特殊场景</li>
 * </ul>
 *
 * @see MemoryProperties 记忆系统配置属性 —— 提供窗口大小等配置参数
 * @see MessageChatMemory 对话记忆实现 —— 基于 message 表（Phase 2 真源）
 */
@Component
public class ChatMemoryFactory {

    /** 消息 Mapper（message 表，Phase 2 对话历史真源）。 */
    private final MessageMapper messageMapper;

    /** 记忆系统配置属性，提供窗口大小等配置参数。 */
    private final MemoryProperties props;

    /** System Prompt 版本（随消息落库归因，08 §2.4，由 PromptVersionService 自动管理）。 */
    private final String promptVersion;

    /** 消息加密服务（ADR-4，Phase 3）。 */
    private final EncryptionService encryptionService;

    /**
     * @param messageMapper  消息 Mapper
     * @param props          记忆配置
     * @param promptVersionService  Prompt 版本管理（自动检测并递增）
     */
    public ChatMemoryFactory(MessageMapper messageMapper,
                             MemoryProperties props,
                             PromptVersionService promptVersionService,
                             EncryptionService encryptionService) {
        this.messageMapper = messageMapper;
        this.props = props;
        this.promptVersion = promptVersionService.getCurrentVersion();
        this.encryptionService = encryptionService;
    }

    /**
     * <h3>创建普通对话记忆</h3>
     *
     * <p>用于一般的用户-AI 对话场景。窗口大小由 {@code app.memory.window-size} 配置决定
     *（默认 20 条消息）。适用于：</p>
     * <ul>
     *   <li>普通的一对一情感咨询对话</li>
     *   <li>不需要长上下文的小型交互场景</li>
     * </ul>
     *
     * @return 使用默认窗口大小的 ChatMemory 实例
     */
    public ChatMemory create() {
        return create(props.getWindowSize());
    }

    /**
     * <h3>创建 Agent 对话记忆</h3>
     *
     * <p>用于 Agent 场景（如 LoveManus 多步骤推理代理），Agent 需要更大的上下文窗口
     * 来追踪多步骤操作的状态和执行结果。窗口大小由 {@code app.memory.agent-window-size}
     * 配置决定（默认 50 条消息）。</p>
     *
     * @return 使用 Agent 窗口大小的 ChatMemory 实例
     */
    public ChatMemory createForAgent() {
        return create(props.getAgentWindowSize());
    }

    /**
     * <h3>使用指定窗口大小创建对话记忆</h3>
     *
     * @param windowSize 上下文窗口大小（get 返回最近 N 条消息）
     * @return 基于 message 表的 ChatMemory 实例
     */
    public ChatMemory create(int windowSize) {
        return new MessageChatMemory(messageMapper, windowSize, promptVersion, encryptionService);
    }
}
