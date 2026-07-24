package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.memory.config.MemoryProperties;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.stereotype.Component;

/**
 * ChatMemory unified factory.
 * <p>
 * Previously LoveApp and ChatService each hardcoded MessageWindowChatMemory (one with 10, one with 50),
 * configurations were scattered and window sizes inconsistent. Now unified through the factory, with configuration centralized in MemoryProperties.
 */
@Component
public class ChatMemoryFactory {

    private final JdbcChatMemoryRepository repository;
    private final MemoryProperties props;

    public ChatMemoryFactory(JdbcChatMemoryRepository repository, MemoryProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** Create normal conversation memory (window = windowSize) */
    public ChatMemory create() {
        return create(props.getWindowSize());
    }

    /** Create Agent conversation memory (window = agentWindowSize) */
    public ChatMemory createForAgent() {
        return create(props.getAgentWindowSize());
    }

    /** Create with specified window size */
    public ChatMemory create(int windowSize) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(windowSize)
                .build();
    }
}
