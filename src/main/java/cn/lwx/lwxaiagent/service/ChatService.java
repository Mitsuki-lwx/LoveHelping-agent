package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.agent.LoveManus;
import cn.lwx.lwxaiagent.infrastructure.ai.LoveApp;
import cn.lwx.lwxaiagent.evolution.SessionTracker;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatService {

    private final LoveApp loveApp;
    private final SessionTracker sessionTracker;
    private final ToolCallback[] toolCallbacks;
    private final ChatModel chatModel;
    private final JdbcChatMemoryRepository chatMemoryRepository;
    private final RateLimiter rateLimiter;
    private final ConcurrentHashMap<String, LoveManus> activeSessions = new ConcurrentHashMap<>();

    public ChatService(LoveApp loveApp, SessionTracker sessionTracker,
                       ToolCallback[] toolCallbacks, ChatModel chatModel,
                       JdbcChatMemoryRepository chatMemoryRepository,
                       RateLimiter rateLimiter) {
        this.loveApp = loveApp;
        this.sessionTracker = sessionTracker;
        this.toolCallbacks = toolCallbacks;
        this.chatModel = chatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.rateLimiter = rateLimiter;
    }

    public String syncChat(String prompt, String chatId) {
        String tenantId = TenantContext.getTenantId();
        rateLimiter.checkQuota(tenantId);
        String result = loveApp.doChat(prompt, chatId);
        rateLimiter.increment(tenantId);
        return result;
    }

    public Flux<String> streamChat(String prompt, String chatId, String tenantId) {
        rateLimiter.checkQuota(tenantId);
        return loveApp.doChatByStream(prompt, chatId)
                .doFinally(sig -> {
                    rateLimiter.increment(tenantId);
                    sessionTracker.onMessageSent(chatId, tenantId);
                });
    }

    public Flux<String> streamChatWithTools(String prompt, String chatId, String tenantId) {
        rateLimiter.checkQuota(tenantId);
        return loveApp.doChatByStreamWithTools(prompt, chatId)
                .doFinally(sig -> {
                    rateLimiter.increment(tenantId);
                    sessionTracker.onMessageSent(chatId, tenantId);
                });
    }

    public Flux<String> streamChatWithRAG(String prompt, String chatId, String tenantId) {
        rateLimiter.checkQuota(tenantId);
        return loveApp.doChatByStreamWithRAG(prompt, chatId)
                .doFinally(sig -> {
                    rateLimiter.increment(tenantId);
                    sessionTracker.onMessageSent(chatId, tenantId);
                });
    }

    public SseEmitter agentChat(String message, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        String finalSessionId = sessionId;

        LoveManus loveManus = activeSessions.get(finalSessionId);
        if (loveManus == null) {
            loveManus = new LoveManus(toolCallbacks, chatModel);
            ChatMemory mysqlMemory = MessageWindowChatMemory.builder()
                    .chatMemoryRepository(chatMemoryRepository)
                    .maxMessages(50)
                    .build();
            loveManus.setChatMemory(mysqlMemory);
            loveManus.setConversationId(finalSessionId);
            activeSessions.put(finalSessionId, loveManus);
        } else {
            loveManus.resetForNextTurn();
        }

        SseEmitter emitter = loveManus.runStream(message);
        emitter.onTimeout(() -> activeSessions.remove(finalSessionId));
        emitter.onError(e -> activeSessions.remove(finalSessionId));

        return emitter;
    }

    public String stopAgent(String sessionId) {
        LoveManus agent = activeSessions.get(sessionId);
        if (agent != null) {
            agent.stop();
            activeSessions.remove(sessionId);
            return "stopped";
        }
        return "no_active_session";
    }
}
