package cn.lwx.lwxaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 记忆管理服务。
 * <p>
 * 对外提供对话记忆的查询、清空、计数能力。
 * 不关心底层是 JDBC、Redis 还是文件，统一通过 ChatMemoryRepository 操作。
 */
@Slf4j
@Service
public class MemoryService {

    private final ChatMemoryRepository chatMemoryRepository;

    public MemoryService(ChatMemoryRepository chatMemoryRepository) {
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /** 获取对话的全部消息 */
    public List<Message> getHistory(String conversationId) {
        return chatMemoryRepository.findByConversationId(conversationId);
    }

    /** 获取对话消息条数 */
    public int getMessageCount(String conversationId) {
        return chatMemoryRepository.findByConversationId(conversationId).size();
    }

    /** 清空指定对话的全部记忆 */
    public void clearHistory(String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
        log.info("Cleared memory for conversation: {}", conversationId);
    }
}
