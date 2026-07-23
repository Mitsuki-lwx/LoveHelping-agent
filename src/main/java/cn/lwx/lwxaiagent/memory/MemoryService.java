package cn.lwx.lwxaiagent.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

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
    private final JdbcTemplate jdbcTemplate;

    public MemoryService(ChatMemoryRepository chatMemoryRepository, JdbcTemplate jdbcTemplate) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 获取对话的全部消息 */
    public List<Message> getHistory(String conversationId) {
        return chatMemoryRepository.findByConversationId(conversationId);
    }

    /** 获取对话消息条数 */
    public int getMessageCount(String conversationId) {
        return chatMemoryRepository.findByConversationId(conversationId).size();
    }

    /** 注册对话归属（用户 + 对话ID 映射） */
    public void registerConversation(String userId, String conversationId, String title, String chatType) {
        try {
            jdbcTemplate.update("""
                INSERT IGNORE INTO user_conversations (user_id, conversation_id, title, chat_type)
                VALUES (?, ?, ?, ?)
                """, userId, conversationId, title != null ? title.substring(0, Math.min(title.length(), 200)) : "", chatType);
        } catch (Exception e) {
            log.warn("Failed to register conversation: {}", e.getMessage());
        }
    }

    /** 列出当前用户的对话记录（含消息数） */
    public List<Map<String, Object>> listUserConversations(String userId, String chatType) {
        try {
            String sql = """
                SELECT uc.conversation_id, uc.title, uc.chat_type, uc.created_at,
                       COALESCE(m.msg_count, 0) AS message_count
                FROM user_conversations uc
                LEFT JOIN (
                    SELECT conversation_id, COUNT(*) AS msg_count
                    FROM SPRING_AI_CHAT_MEMORY
                    GROUP BY conversation_id
                ) m ON uc.conversation_id = m.conversation_id
                WHERE uc.user_id = ? AND uc.chat_type = ?
                ORDER BY uc.created_at DESC
                LIMIT 100
            """;
            return jdbcTemplate.queryForList(sql, userId, chatType);
        } catch (Exception e) {
            log.warn("Failed to list user conversations: {}", e.getMessage());
            return List.of();
        }
    }

    /** 列出所有对话记录（管理员用） */
    public List<Map<String, Object>> listAllConversations() {
        try {
            String sql = """
                SELECT conversation_id, COUNT(*) AS message_count,
                       MIN(`timestamp`) AS created_at
                FROM SPRING_AI_CHAT_MEMORY
                GROUP BY conversation_id
                ORDER BY MIN(`timestamp`) DESC
                LIMIT 100
            """;
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("Failed to list conversations: {}", e.getMessage());
            return List.of();
        }
    }

    /** 清空指定对话的全部记忆，并删除映射关系 */
    @Transactional
    public void clearHistory(String conversationId) {
        chatMemoryRepository.deleteByConversationId(conversationId);
        try {
            jdbcTemplate.update("DELETE FROM user_conversations WHERE conversation_id = ?", conversationId);
        } catch (Exception e) {
            log.warn("Failed to delete conversation mapping: {}", e.getMessage());
        }
        log.info("Cleared memory for conversation: {}", conversationId);
    }
}
