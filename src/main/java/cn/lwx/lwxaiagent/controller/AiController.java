package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry;
import cn.lwx.lwxaiagent.service.ChatService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <h1>AI 聊天控制器（编排层重构后）</h1>
 *
 * <p>所有聊天请求统一经过 {@link ChatEntry}（唯一入口），由路由器自动判断
 * 能力叠加 + 深度（普通/增强浅层/增强深层），控制器只做参数提取和类型转换。</p>
 *
 * <p>Agent 任务（/LoveManus）也走 ChatEntry——路由器自动升级到 DEEP（ReactAgent）。
 * /LoveManus 保留为向后兼容别名，forceAgent=true。</p>
 *
 * <p>任务查询/停止是管理操作，直接调 ChatService。</p>
 */
@RestController
public class AiController {

    private final ChatEntry chatEntry;
    private final ChatService chatService;

    public AiController(ChatEntry chatEntry, ChatService chatService) {
        this.chatEntry = chatEntry;
        this.chatService = chatService;
    }

    // ==================== 聊天端点（全部走 ChatEntry）====================

    /**
     * 同步聊天（legacy 直通）：走 ChatEntry → Router → ChatExecutor(shallow) → block Flux。
     */
    @GetMapping("Love_app/chat/sync")
    public Result<String> chatSync(@RequestParam String prompt, @RequestParam String chatId) {
        AgentResult result = chatEntry.chat(prompt, chatId, List.of(), false, null);
        if (result instanceof AgentResult.ShallowResult sr) {
            return Result.ok(sr.flux().collectList().block().stream().reduce(String::concat).orElse(""));
        }
        return Result.ok("sync not supported for deep mode");
    }

    /**
     * SSE 流式聊天（含 mediaIds 条件分流）：走 ChatEntry → Router 自动判断能力。
     */
    @GetMapping(value = "Love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSse(@RequestParam String prompt, @RequestParam String chatId,
                                @RequestParam(required = false) String mediaIds) {
        List<Long> ids = parseMediaIds(mediaIds);
        AgentResult result = chatEntry.chat(prompt, chatId, ids, false, null);
        if (result instanceof AgentResult.ShallowResult sr) {
            return sr.flux();
        }
        // Deep 结果走 SseEmitter → Flux 桥接（保持 SSE 格式兼容）
        SseEmitter emitter = ((AgentResult.DeepResult) result).emitter();
        return Flux.create(sink -> {
            emitter.onTimeout(() -> sink.complete());
            emitter.onError(e -> sink.error(e));
            emitter.onCompletion(() -> sink.complete());
        });
    }

    /**
     * SSE 流式聊天（返回 ServerSentEvent 格式包装）。
     */
    @GetMapping(value = "Love_app/chat/sse")
    public Flux<org.springframework.http.codec.ServerSentEvent<String>> chatSseServer(
            @RequestParam String prompt, @RequestParam String chatId,
            @RequestParam(required = false) String mediaIds) {
        List<Long> ids = parseMediaIds(mediaIds);
        AgentResult result = chatEntry.chat(prompt, chatId, ids, false, null);
        Flux<String> stream = switch (result) {
            case AgentResult.ShallowResult sr -> sr.flux();
            case AgentResult.DeepResult dr -> {
                SseEmitter emitter = dr.emitter();
                yield Flux.<String>create(sink -> {
                    emitter.onTimeout(() -> sink.complete());
                    emitter.onError(sink::error);
                    emitter.onCompletion(() -> sink.complete());
                });
            }
        };
        return stream.map(s -> org.springframework.http.codec.ServerSentEvent.<String>builder(s).data(s).build());
    }

    /**
     * SseEmitter 桥接流式聊天。
     */
    @GetMapping(value = "Love_app/chat/sse_emitter")
    public SseEmitter chatSseEmitter(@RequestParam String prompt, @RequestParam String chatId) {
        AgentResult result = chatEntry.chat(prompt, chatId, List.of(), false, null);
        if (result instanceof AgentResult.DeepResult dr) {
            return dr.emitter();
        }
        // ShallowResult → Flux → SseEmitter 桥接
        SseEmitter emitter = new SseEmitter();
        ((AgentResult.ShallowResult) result).flux().subscribe(
                trunk -> { try { emitter.send(trunk); } catch (Exception e) { emitter.completeWithError(e); } },
                error -> emitter.completeWithError(error),
                () -> emitter.complete()
        );
        return emitter;
    }

    /**
     * RAG 流式聊天（legacy 别名）：强制启用 RAG 能力。
     * 保留为向后兼容端点（smoke 7.3 / 前端历史代码）。
     */
    @GetMapping(value = "Love_app/chat/sse/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSseWithRAG(@RequestParam String prompt, @RequestParam String chatId) {
        // forceAgent=true → Agent 有 KnowledgeSearchTool 可用，可自行决定是否检索知识库
        AgentResult result = chatEntry.chat(prompt, chatId, List.of(), true, null);
        if (result instanceof AgentResult.DeepResult dr) {
            SseEmitter emitter = dr.emitter();
            return Flux.<String>create(sink -> {
                emitter.onTimeout(() -> sink.complete());
                emitter.onError(sink::error);
                emitter.onCompletion(() -> sink.complete());
            });
        }
        return Flux.empty();
    }

    // ==================== Agent 任务端点（走 ChatEntry 自动升级 DEEP）====================

    /**
     * 智能体对话接口（向后兼容 /LoveManus，内部走 ChatEntry → DEEP）。
     * 保留 X-Session-Id 响应头 + 幂等键，向后兼容前端。
     */
    @GetMapping(value = "Love_app/chat/LoveManus")
    public SseEmitter doChatWithLoveManus(@RequestParam String message, String sessionId,
                                          @RequestParam(required = false) String idempotencyKey,
                                          HttpServletResponse response) {
        // forceAgent=true：路由器强制走 DEEP（ReactAgent），自动升级
        AgentResult result = chatEntry.chat(message, sessionId, List.of(), true, null);
        if (result instanceof AgentResult.DeepResult dr) {
            response.setHeader("X-Session-Id", sessionId);
            return dr.emitter();
        }
        // 不应发生（forceAgent=true 必走 DEEP）
        throw new IllegalStateException("Expected DeepResult for agent path");
    }

    /**
     * 查询 Agent 任务状态（管理操作，直接调 ChatService）。
     */
    @GetMapping("Love_app/chat/LoveManus/task/{taskId}")
    public Result<cn.lwx.lwxaiagent.entity.AgentTask> getAgentTask(@PathVariable Long taskId) {
        return Result.ok(chatService.getAgentTask(taskId));
    }

    /**
     * 停止运行中的 Agent 会话（管理操作，直接调 ChatService）。
     */
    @GetMapping("Love_app/chat/LoveManus/stop/{sessionId}")
    public Result<String> stopLoveManus(@PathVariable String sessionId) {
        return Result.ok(chatService.stopAgent(sessionId));
    }

    // ==================== 工具方法 ====================

    /** 解析 mediaIds 字符串为 Long 列表（逗号分隔，空白过滤） */
    private List<Long> parseMediaIds(String mediaIds) {
        if (mediaIds == null || mediaIds.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(mediaIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();
    }
}
