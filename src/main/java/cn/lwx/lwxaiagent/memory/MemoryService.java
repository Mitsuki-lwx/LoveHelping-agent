package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h1>记忆管理服务 —— 对话记忆的查询、清理与统计</h1>
 *
 * <p><strong>核心作用：</strong>提供对外的对话记忆管理能力，包括历史消息查询、消息计数、
 * 对话归属注册、对话列表查询和记忆清除等功能。操作对象为 {@code message} 表
 * （Phase 2 对话历史真源），取代旧的 {@code SPRING_AI_CHAT_MEMORY}。</p>
 *
 * <h2>数据存储结构</h2>
 * <ul>
 *   <li><b>{@code message}：</b>对话消息表（Phase 2 真源），含软删（deleted）、反馈（feedback）、
 *       prompt_version 等业务字段。通过 {@link MessageMapper}（MyBatis-Plus）操作</li>
 *   <li><b>{@code user_conversations}：</b>用户-对话映射表，
 *       记录每个对话属于哪个用户、对话标题和对话类型。通过 {@link JdbcTemplate} 直接操作</li>
 * </ul>
 *
 * @see ChatMemoryFactory 聊天记忆工厂 —— 创建 ChatMemory 实例
 * @see MessageChatMemory 对话记忆实现 —— 基于 message 表
 */
@Slf4j
@Service
public class MemoryService {

    /** 消息 Mapper（message 表，Phase 2 对话历史真源）。 */
    private final MessageMapper messageMapper;

    /** Spring JDBC 模板，用于执行自定义 SQL（如操作用户-对话映射表）。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param messageMapper 消息 Mapper
     * @param jdbcTemplate  Spring JDBC 模板
     */
    public MemoryService(MessageMapper messageMapper, JdbcTemplate jdbcTemplate) {
        this.messageMapper = messageMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * <h3>查询会话归属用户</h3>
     *
     * @param conversationId 会话 ID
     * @return 归属用户 ID；会话未被任何用户注册过时返回 {@code null}
     */
    public String getOwnerUserId(String conversationId) {
        List<String> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM user_conversations WHERE conversation_id = ? LIMIT 1",
                String.class, conversationId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    /**
     * <h3>获取指定会话的完整历史消息</h3>
     *
     * <p>从 {@code message} 表按会话 ID 查询所有未删除消息，按创建顺序排列。</p>
     *
     * @param conversationId 会话 ID
     * @return 按时间顺序排列的消息列表，会话不存在或已删则返回空列表
     */
    public List<org.springframework.ai.chat.messages.Message> getHistory(String conversationId) {
        List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getDeleted, 0)
                .orderByAsc(Message::getId));
        List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>(rows.size());
        for (Message row : rows) {
            String content = row.getContent() == null ? "" : row.getContent();
            String role = row.getRole() == null ? "" : row.getRole().toUpperCase();
            switch (role) {
                case "USER" -> result.add(new UserMessage(content));
                case "SYSTEM" -> result.add(new SystemMessage(content));
                default -> result.add(new AssistantMessage(content));
            }
        }
        return result;
    }

    /**
     * <h3>获取指定会话的消息数量</h3>
     *
     * <p>数据库层面 COUNT 未删除消息，避免全量加载到内存。</p>
     *
     * @param conversationId 会话 ID
     * @return 会话中的未删除消息总数
     */
    public int getMessageCount(String conversationId) {
        return Math.toIntExact(messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getDeleted, 0)));
    }

    /**
     * <h3>注册对话归属关系</h3>
     *
     * <p>在 {@code user_conversations} 表中建立用户与对话的归属映射。
     * 使用 {@code INSERT IGNORE} 语法，如果记录已存在则忽略（不抛出异常）。
     * 注册失败仅记录 WARN，不阻断核心对话流程。</p>
     */
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

    /**
     * <h3>列出当前用户的对话记录（含消息数量）</h3>
     *
     * <p>从 {@code user_conversations} 查询用户的对话记录，LEFT JOIN {@code message}
     * 统计每个对话的未删除消息数量。</p>
     */
    public List<Map<String, Object>> listUserConversations(String userId, String chatType) {
        try {
            String sql = """
                SELECT uc.conversation_id, uc.title, uc.chat_type, uc.created_at,
                       COALESCE(m.msg_count, 0) AS message_count
                FROM user_conversations uc
                LEFT JOIN (
                    SELECT conversation_id, COUNT(*) AS msg_count
                    FROM message
                    WHERE deleted = 0
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

    /**
     * <h3>列出所有对话记录（管理员功能）</h3>
     *
     * <p>从 {@code message} 表按会话 ID 分组统计未删除消息数量，不依赖归属表。</p>
     */
    public List<Map<String, Object>> listAllConversations() {
        try {
            String sql = """
                SELECT conversation_id, COUNT(*) AS message_count,
                       MIN(created_at) AS created_at
                FROM message
                WHERE deleted = 0
                GROUP BY conversation_id
                ORDER BY MIN(created_at) DESC
                LIMIT 100
            """;
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("Failed to list conversations: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * <h3>提交单条消息反馈（LIKE/DISLIKE）</h3>
     *
     * <p>更新 {@code message.feedback}。先校验消息所属会话归属当前用户（防 IDOR）。</p>
     *
     * @param messageId 消息 ID
     * @param value     LIKE / DISLIKE / NONE
     * @param userId    当前用户 ID（归属校验）
     */
    @Transactional
    public void feedbackMessage(Long messageId, String value, String userId) {
        Message msg = messageMapper.selectById(messageId);
        if (msg == null || Integer.valueOf(1).equals(msg.getDeleted())) {
            throw new BizException(404, "消息不存在");
        }
        String owner = getOwnerUserId(msg.getConversationId());
        if (owner != null && !owner.equals(userId)) {
            throw new BizException(403, "无权操作该消息");
        }
        String v = (value == null || value.isBlank()) ? "NONE" : value.toUpperCase();
        if (!List.of("LIKE", "DISLIKE", "NONE").contains(v)) {
            throw new BizException(400, "feedback 取值仅支持 LIKE/DISLIKE/NONE");
        }
        messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                .eq(Message::getId, messageId)
                .set(Message::getFeedback, v));
        log.info("Feedback set: messageId={}, value={}, user={}", messageId, v, userId);
    }

    /**
     * <h3>清除指定会话的全部记忆及映射关系</h3>
     *
     * <p>事务性：软删 {@code message}（deleted=1）并删除 {@code user_conversations} 归属记录。</p>
     */
    @Transactional
    public void clearHistory(String conversationId) {
        try {
            messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .set(Message::getDeleted, 1));
        } catch (Exception e) {
            log.warn("Failed to soft-delete messages: {}", e.getMessage());
        }
        try {
            jdbcTemplate.update("DELETE FROM user_conversations WHERE conversation_id = ?", conversationId);
        } catch (Exception e) {
            log.warn("Failed to delete conversation mapping: {}", e.getMessage());
        }
        log.info("Cleared memory for conversation: {}", conversationId);
    }
}
