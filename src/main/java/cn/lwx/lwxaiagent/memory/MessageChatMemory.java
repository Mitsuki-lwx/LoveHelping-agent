package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>基于 {@code message} 表的对话记忆（Phase 2，取代 SPRING_AI_CHAT_MEMORY）</h1>
 *
 * <p>实现 Spring AI {@link ChatMemory} 接口，作为 {@code MessageChatMemoryAdvisor} 的
 * 底层记忆存储，直接读写 {@code message} 表：</p>
 * <ul>
 *   <li><b>append-only</b>：{@link #add} 逐条 INSERT，保留全量历史（审计/回看地基）；</li>
 *   <li><b>窗口读取</b>：{@link #get} 返回最近 N 条（windowSize），供上下文窗口注入；</li>
 *   <li><b>软删</b>：{@link #clear} 置 {@code deleted=1}，不物理删除（ADR-5 配物理清除）。</li>
 * </ul>
 *
 * <p>旧实现 {@code JdbcChatMemoryRepository + MessageWindowChatMemory} 依赖
 * {@code SPRING_AI_CHAT_MEMORY} 表（只存窗口、无业务字段），已被本类取代。</p>
 *
 * @see ChatMemoryFactory
 */
@Slf4j
public class MessageChatMemory implements ChatMemory {

    private final MessageMapper messageMapper;
    private final int windowSize;
    private final String promptVersion;

    /**
     * @param windowSize     上下文窗口大小（get 返回最近 N 条）
     * @param promptVersion  System Prompt 版本，随消息落库归因（08 §2.4）
     */
    public MessageChatMemory(MessageMapper messageMapper, int windowSize, String promptVersion) {
        this.messageMapper = messageMapper;
        this.windowSize = windowSize;
        this.promptVersion = promptVersion;
    }

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";
        List<Message> rows = new ArrayList<>();
        for (org.springframework.ai.chat.messages.Message m : messages) {
            String text = textOf(m);
            if (text == null || text.isBlank()) {
                continue; // 纯工具调用/媒体块不落原始文本
            }
            Message row = new Message();
            row.setConversationId(conversationId);
            row.setUserId(userId);
            row.setRole(roleOf(m.getMessageType()));
            row.setContent(text);
            row.setContentHmac(hmac(text));
            row.setPromptVersion(promptVersion);
            row.setFeedback("NONE");
            row.setDeleted(0);
            rows.add(row);
        }
        try {
            for (Message row : rows) {
                try {
                    messageMapper.insert(row);
                } catch (Exception e) {
                    // 单条失败不阻断其余落库，也不阻断对话主流程
                    log.warn("MessageChatMemory.add row failed: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("MessageChatMemory.add failed: {}", e.getMessage());
        }
    }

    @Override
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        try {
            List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .eq(Message::getDeleted, 0)
                    .orderByDesc(Message::getId)
                    .last("LIMIT " + windowSize));
            List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>(rows.size());
            for (int i = rows.size() - 1; i >= 0; i--) { // 倒排回时间正序
                Message row = rows.get(i);
                String role = row.getRole();
                String content = row.getContent() == null ? "" : row.getContent();
                switch (role == null ? "" : role.toUpperCase()) {
                    case "USER" -> result.add(new UserMessage(content));
                    case "SYSTEM" -> result.add(new SystemMessage(content));
                    case "TOOL" -> result.add(new AssistantMessage(content));
                    default -> result.add(new AssistantMessage(content));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("MessageChatMemory.get failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .set(Message::getDeleted, 1));
        } catch (Exception e) {
            log.warn("MessageChatMemory.clear failed: {}", e.getMessage());
        }
    }

    /** 提取消息文本（USER/ASSISTANT/SYSTEM 直接取文本；纯媒体块忽略） */
    private String textOf(org.springframework.ai.chat.messages.Message m) {
        try {
            return m.getText();
        } catch (Exception e) {
            return null;
        }
    }

    private String roleOf(MessageType type) {
        if (type == null) {
            return "ASSISTANT";
        }
        return switch (type) {
            case USER -> "USER";
            case SYSTEM -> "SYSTEM";
            case TOOL -> "TOOL";
            default -> "ASSISTANT";
        };
    }

    private String hmac(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
