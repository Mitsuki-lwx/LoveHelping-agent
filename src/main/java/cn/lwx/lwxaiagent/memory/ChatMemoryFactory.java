package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.memory.config.MemoryProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.stereotype.Component;

/**
 * ChatMemory 统一工厂。
 * <p>
 * 之前 LoveApp 和 ChatService 各自硬编码 MessageWindowChatMemory（一个 10、一个 50），
 * 配置散落、窗口大小不一致。现在统一走工厂，配置集中在 MemoryProperties。
 */
@Component
public class ChatMemoryFactory {

    private final JdbcChatMemoryRepository repository;
    private final MemoryProperties props;

    public ChatMemoryFactory(JdbcChatMemoryRepository repository, MemoryProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** 创建普通对话记忆（窗口 = windowSize） */
    public ChatMemory create() {
        return create(props.getWindowSize());
    }

    /** 创建 Agent 对话记忆（窗口 = agentWindowSize） */
    public ChatMemory createForAgent() {
        return create(props.getAgentWindowSize());
    }

    /** 按指定窗口大小创建 */
    public ChatMemory create(int windowSize) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(windowSize)
                .build();
    }
}
