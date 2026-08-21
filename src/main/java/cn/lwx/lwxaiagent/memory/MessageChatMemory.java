package cn.lwx.lwxaiagent.memory;

import cn.lwx.lwxaiagent.entity.Message;
import cn.lwx.lwxaiagent.infrastructure.EncryptionService;
import cn.lwx.lwxaiagent.mapper.MessageMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class MessageChatMemory implements ChatMemory {

    private final MessageMapper messageMapper;
    private final int windowSize;
    private final String promptVersion;
    private final EncryptionService encryptionService;

    public MessageChatMemory(MessageMapper messageMapper, int windowSize,
                             String promptVersion, EncryptionService encryptionService) {
        this.messageMapper = messageMapper;
        this.windowSize = windowSize;
        this.promptVersion = promptVersion;
        this.encryptionService = encryptionService;
    }

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) return;
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";
        List<Message> rows = new ArrayList<>();
        for (org.springframework.ai.chat.messages.Message m : messages) {
            String text = textOf(m);
            if (text == null || text.isBlank()) continue;
            Message row = new Message();
            row.setConversationId(conversationId);
            row.setUserId(userId);
            row.setRole(roleOf(m.getMessageType()));
            row.setContent(encryptionService.encrypt(text, userId));
            row.setContentHmac(encryptionService.hmac(text));
            row.setPromptVersion(promptVersion);
            row.setFeedback("NONE");
            row.setDeleted(0);
            rows.add(row);
        }
        try {
            for (Message row : rows) {
                try { messageMapper.insert(row); }
                catch (Exception e) { log.warn("MessageChatMemory.add row failed: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.warn("MessageChatMemory.add failed: {}", e.getMessage());
        }
    }

    @Override
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
        if (conversationId == null) return List.of();
        try {
            List<Message> rows = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .eq(Message::getDeleted, 0)
                    .orderByDesc(Message::getId)
                    .last("LIMIT " + windowSize));
            List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>(rows.size());
            for (int i = rows.size() - 1; i >= 0; i--) {
                Message row = rows.get(i);
                String content = decryptContent(row.getContent(), row.getUserId());
                switch (roleOf(row.getRole())) {
                    case "USER" -> result.add(new UserMessage(content));
                    case "SYSTEM" -> result.add(new SystemMessage(content));
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
        if (conversationId == null) return;
        try {
            messageMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Message>()
                    .eq(Message::getConversationId, conversationId)
                    .set(Message::getDeleted, 1));
        } catch (Exception e) {
            log.warn("MessageChatMemory.clear failed: {}", e.getMessage());
        }
    }

    private String textOf(org.springframework.ai.chat.messages.Message m) {
        try { return m.getText(); } catch (Exception e) { return null; }
    }

    private String roleOf(MessageType type) {
        if (type == null) return "ASSISTANT";
        return switch (type) { case USER -> "USER"; case SYSTEM -> "SYSTEM"; default -> "ASSISTANT"; };
    }

    private String roleOf(String role) {
        return role == null ? "ASSISTANT" : role.toUpperCase();
    }

    private String decryptContent(String content, String userId) {
        try { return encryptionService.decrypt(content, userId); }
        catch (Exception e) { return content; }
    }
}