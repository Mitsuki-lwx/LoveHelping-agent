package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.ChatService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@RestController
public class AiController {

    private final ChatService chatService;

    public AiController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("Love_app/chat/sync")
    public Result<String> chatSync(String prompt, String chatId) {
        return Result.ok(chatService.syncChat(prompt, chatId));
    }

    @GetMapping(value = "Love_app/chat/sse/tools", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSseWithTools(String prompt, String chatId) {
        return chatService.streamChatWithTools(prompt, chatId, TenantContext.getTenantId());
    }

    @GetMapping(value = "Love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSse(String prompt, String chatId) {
        return chatService.streamChat(prompt, chatId, TenantContext.getTenantId());
    }

    @GetMapping(value = "Love_app/chat/sse")
    public Flux<ServerSentEvent<String>> chatSseServer(String prompt, String chatId) {
        return chatService.streamChat(prompt, chatId, TenantContext.getTenantId())
                .map(trunk -> ServerSentEvent.<String>builder(trunk).data(trunk).build());
    }

    @GetMapping(value = "Love_app/chat/sse_emitter")
    public SseEmitter chatSseEmitter(String prompt, String chatId) {
        SseEmitter emitter = new SseEmitter();
        chatService.streamChat(prompt, chatId, TenantContext.getTenantId())
                .subscribe(
                        trunk -> {
                            try {
                                emitter.send(trunk);
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> emitter.completeWithError(error),
                        () -> emitter.complete()
                );
        return emitter;
    }

    @GetMapping(value = "Love_app/chat/sse/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSseWithRAG(String prompt, String chatId) {
        return chatService.streamChatWithRAG(prompt, chatId, TenantContext.getTenantId());
    }

    @GetMapping(value = "Love_app/chat/LoveManus")
    public SseEmitter doChatWithLoveManus(String message, String sessionId, HttpServletResponse response) {
        SseEmitter emitter = chatService.agentChat(message, sessionId);
        response.setHeader("X-Session-Id", sessionId);
        return emitter;
    }

    @GetMapping("Love_app/chat/LoveManus/stop/{sessionId}")
    public Result<String> stopLoveManus(@PathVariable String sessionId) {
        return Result.ok(chatService.stopAgent(sessionId));
    }
}
