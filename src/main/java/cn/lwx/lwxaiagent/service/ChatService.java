package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.agent.LoveManus;
import cn.lwx.lwxaiagent.infrastructure.ai.LoveApp;
import cn.lwx.lwxaiagent.evolution.SessionTracker;
import cn.lwx.lwxaiagent.evolution.SkillRetriever;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService {

    private final LoveApp loveApp;
    private final SessionTracker sessionTracker;
    private final SkillRetriever skillRetriever;
    private final ToolCallback[] toolCallbacks;
    private final ChatModel chatModel;
    private final ChatMemoryFactory chatMemoryFactory;
    private final RateLimiter rateLimiter;
    private final ConcurrentHashMap<String, LoveManus> activeSessions = new ConcurrentHashMap<>();

    public ChatService(LoveApp loveApp, SessionTracker sessionTracker,
                       SkillRetriever skillRetriever,
                       ToolCallback[] toolCallbacks, ChatModel chatModel,
                       ChatMemoryFactory chatMemoryFactory,
                       RateLimiter rateLimiter) {
        this.loveApp = loveApp;
        this.sessionTracker = sessionTracker;
        this.skillRetriever = skillRetriever;
        this.toolCallbacks = toolCallbacks;
        this.chatModel = chatModel;
        this.chatMemoryFactory = chatMemoryFactory;
        this.rateLimiter = rateLimiter;
    }

    public String syncChat(String prompt, String chatId) {
        String tenantId = TenantContext.getTenantId();
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(tid);
        String skillContext = skillRetriever.retrieveAsContext(prompt, tid);
        log.info("syncChat: tenant={}, skillContext={}", tid, skillContext.isEmpty() ? "(none)" : skillContext);
        String result = loveApp.doChat(prompt, chatId, skillContext);
        rateLimiter.increment(tid);
        sessionTracker.onMessageSent(chatId, tid);
        return result;
    }

    public Flux<String> streamChat(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(tid);
        String skillContext = skillRetriever.retrieveAsContext(prompt, tid);
        return loveApp.doChatByStream(prompt, chatId, skillContext)
                .doFinally(sig -> {
                    rateLimiter.increment(tid);
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    public Flux<String> streamChatWithTools(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(tid);
        String skillContext = skillRetriever.retrieveAsContext(prompt, tid);
        return loveApp.doChatByStreamWithTools(prompt, chatId, skillContext)
                .doFinally(sig -> {
                    rateLimiter.increment(tid);
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    public Flux<String> streamChatWithRAG(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(tid);
        String skillContext = skillRetriever.retrieveAsContext(prompt, tid);
        return loveApp.doChatByStreamWithRAG(prompt, chatId, skillContext)
                .doFinally(sig -> {
                    rateLimiter.increment(tid);
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    public SseEmitter agentChat(String message, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        String finalSessionId = sessionId;
        String tenantId = TenantContext.getTenantId();
        String tid = (tenantId != null) ? tenantId : "default";

        LoveManus loveManus = activeSessions.get(finalSessionId);
        if (loveManus == null) {
            loveManus = new LoveManus(toolCallbacks, chatModel);
            ChatMemory agentMemory = chatMemoryFactory.createForAgent();
            loveManus.setChatMemory(agentMemory);
            loveManus.setConversationId(finalSessionId);
            activeSessions.put(finalSessionId, loveManus);
        } else {
            loveManus.resetForNextTurn();
        }

        SseEmitter emitter = loveManus.runStream(message);
        emitter.onTimeout(() -> {
            activeSessions.remove(finalSessionId);
            sessionTracker.onMessageSent(finalSessionId, tid);
        });
        emitter.onError(e -> {
            activeSessions.remove(finalSessionId);
            sessionTracker.onMessageSent(finalSessionId, tid);
        });

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
