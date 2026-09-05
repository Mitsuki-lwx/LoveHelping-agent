package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.infrastructure.orchestration.AgentResult;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatEntry;
import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
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
@lombok.extern.slf4j.Slf4j
public class AiController {

    private final ChatEntry chatEntry;
    private final ChatService chatService;
    private final cn.lwx.lwxaiagent.service.AgentTaskService agentTaskService;
    private final cn.lwx.lwxaiagent.memory.MemoryService memoryService;

    public AiController(ChatEntry chatEntry, ChatService chatService,
                        cn.lwx.lwxaiagent.service.AgentTaskService agentTaskService,
                        cn.lwx.lwxaiagent.memory.MemoryService memoryService) {
        this.chatEntry = chatEntry;
        this.chatService = chatService;
        this.agentTaskService = agentTaskService;
        this.memoryService = memoryService;
    }

    // ==================== 聊天端点（全部走 ChatEntry）====================

    /**
     * 同步聊天（legacy 直通）：走 ChatEntry → Router → ChatExecutor(shallow) → block Flux。
     */
    @GetMapping("Love_app/chat/sync")
    public Result<String> chatSync(@RequestParam String prompt, @RequestParam String chatId,
                                @RequestParam(required = false, defaultValue = "false") boolean continueBrake) {
        AgentResult result = chatEntry.chat(prompt, chatId, List.of(), false, continueBrake, null);
        if (result instanceof AgentResult.ShallowResult sr) {
            return Result.ok(sr.flux()
                    .filter(s -> !s.startsWith(ChatExecutor.ADVICE_EVENT_MARKER))
                    .collectList().block().stream().reduce(String::concat).orElse(""));
        }
        return Result.ok("sync not supported for deep mode");
    }

    /**
     * SSE 流式聊天（含 mediaIds 条件分流）：走 ChatEntry → Router 自动判断能力。
     * 话术三级（FR-CORE-01）：advice 请求流末尾追加标记块 {@code data:@@ADVICE@@{json}}，
     * 非 advice 请求流与旧版完全一致（增量协议，05 §3.1）。
     */
    @GetMapping(value = "Love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSse(@RequestParam String prompt, @RequestParam String chatId,
                                @RequestParam(required = false) String mediaIds,
                                @RequestParam(required = false, defaultValue = "false") boolean continueBrake) {
        List<Long> ids = parseMediaIds(mediaIds);
        AgentResult result;
        try {
            result = chatEntry.chat(prompt, chatId, ids, false, continueBrake, null);
        } catch (BizException e) {
            // 护栏阻断（L3/情绪刹车片/prompt 探查拦截）在入口同步抛出——若冒泡出 controller
            // 会返回 JSON 而非 SSE 流，前端按流解析即"空白"（2026-09-03 实测）。
            // 改为把给用户的话术作为一条 SSE 消息发出（输出层 ERROR 事件，见 ADR-21）
            return Flux.just(e.getMessage());
        }
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
            @RequestParam(required = false) String mediaIds,
            @RequestParam(required = false, defaultValue = "false") boolean continueBrake) {
        List<Long> ids = parseMediaIds(mediaIds);
        AgentResult result;
        try {
            result = chatEntry.chat(prompt, chatId, ids, false, continueBrake, null);
        } catch (BizException e) {
            // 同 chatSse：入口阻断话术作为默认 data 事件发出（不设 event 名，触发前端 onmessage）
            return Flux.just(org.springframework.http.codec.ServerSentEvent
                    .<String>builder(e.getMessage()).build());
        }
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
        // 话术三级（FR-CORE-01）：@@ADVICE@@ 标记块转成独立 advice 事件，其余为纯文本 data 事件（向后兼容）
        return stream.flatMap(s -> {
            if (s.startsWith(ChatExecutor.ADVICE_EVENT_MARKER)) {
                String json = s.substring(ChatExecutor.ADVICE_EVENT_MARKER.length());
                return Flux.just(org.springframework.http.codec.ServerSentEvent.<String>builder(json).event("advice").build());
            }
            return Flux.just(org.springframework.http.codec.ServerSentEvent.<String>builder(s).data(s).build());
        });
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
        ((AgentResult.ShallowResult) result).flux()
                .filter(s -> !s.startsWith(ChatExecutor.ADVICE_EVENT_MARKER))
                .subscribe(
                trunk -> { try { emitter.send(trunk); } catch (Exception e) { emitter.completeWithError(e); } },
                error -> emitter.completeWithError(error),
                () -> emitter.complete()
        );
        return emitter;
    }

    /**
     * RAG 流式聊天（legacy 别名）：强制走工具/Agent 通道。
     * 保留为向后兼容端点（smoke 7.3 / 前端历史代码）。
     */
    @GetMapping(value = "Love_app/chat/sse/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatSseWithRAG(@RequestParam String prompt, @RequestParam String chatId) {
        AgentResult result = chatEntry.chat(prompt, chatId, List.of(), true, null);
        // 全部路径收编到图（ADR-19），结果统一为 ShallowResult
        return bridgeToEmitter(((AgentResult.ShallowResult) result).flux());
    }

    // ==================== Agent 任务端点（走 ChatEntry 自动升级 DEEP）====================

    /**
     * 智能体对话接口（向后兼容 /LoveManus，内部走 ChatEntry → 编排图 agent 路径）。
     * 保留 X-Session-Id 响应头 + 幂等键，向后兼容前端。
     */
    @GetMapping(value = "Love_app/chat/LoveManus")
    public SseEmitter doChatWithLoveManus(@RequestParam String message, String sessionId,
                                          @RequestParam(required = false) String idempotencyKey,
                                          HttpServletResponse response) {
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";
        // 会话归属注册（记忆萃取前提）：agent 会话须有 user_conversations 映射，否则萃取跳过（记忆缺口修复）
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                memoryService.registerConversation(userId, sessionId, "智能体会话", "agent");
            } catch (Exception e) {
                log.warn("LoveManus conversation register failed: {}", e.getMessage());
            }
        }
        // 任务落库（ADR-3）：PENDING→RUNNING，完成回调 → SUCCESS/FAILED（幂等键防重）
        cn.lwx.lwxaiagent.entity.AgentTask task =
                agentTaskService.submit(userId, message, idempotencyKey);
        agentTaskService.start(task.getId());

        // forceAgent=true：图 classify 强制走工具循环（ADR-19）；执行完成回调驱动任务终态
        AgentResult result = chatEntry.chat(message, sessionId, List.of(), true, (ok, err) -> {
            if (ok) {
                agentTaskService.succeed(task.getId(), null, 0);
            } else {
                agentTaskService.fail(task.getId(), "E1000", err == null ? "graph run failed" : err);
            }
        });
        response.setHeader("X-Session-Id", sessionId);
        return bridgeToEmitter(((AgentResult.ShallowResult) result).flux());
    }

    /** ShallowResult 的 Flux → SseEmitter（文本分块 + 🔧 工具行透传；剥离 advice 标记避免 JSON 打在正文） */
    private static SseEmitter bridgeToEmitter(Flux<String> flux) {
        // 长超时：agent 工具循环（查询改写 + 多轮 LLM）可能超过默认 30s（对齐旧 AgentLoopExecutor 600s）
        SseEmitter emitter = new SseEmitter(600_000L);
        flux
                .filter(s -> !s.startsWith(ChatExecutor.ADVICE_EVENT_MARKER))
                .subscribe(
                        trunk -> { try { emitter.send(trunk); } catch (Exception e) { emitter.completeWithError(e); } },
                        error -> emitter.completeWithError(error),
                        () -> emitter.complete()
                );
        return emitter;
    }

    /**
     * 查询 Agent 任务状态（管理操作，直接调 ChatService）。
     */
    @GetMapping("Love_app/chat/LoveManus/task/{taskId}")
    public Result<cn.lwx.lwxaiagent.entity.AgentTask> getAgentTask(@PathVariable Long taskId) {
        // 安全（2026-09-05 高危修复 #3）：任务归属校验——非本人/非 ADMIN 一律 403
        cn.lwx.lwxaiagent.entity.AgentTask task = agentTaskService.get(taskId);
        String me = TenantContext.getUserId();
        boolean admin = "ADMIN".equals(TenantContext.getRole());
        if (!admin && (task == null || me == null || !me.equals(task.getUserId()))) {
            throw new cn.lwx.lwxaiagent.common.BizException(403, "无权访问该任务");
        }
        return Result.ok(task);
    }

    /**
     * 停止运行中的 Agent 会话（管理操作，直接调 ChatService）。
     */
    @GetMapping("Love_app/chat/LoveManus/stop/{sessionId}")
    public Result<String> stopLoveManus(@PathVariable String sessionId) {
        // 安全（2026-09-05 高危修复 #3）：会话归属校验——匿名/非本人不可停他人会话
        String me = TenantContext.getUserId();
        boolean admin = "ADMIN".equals(TenantContext.getRole());
        if (!admin) {
            String owner = memoryService.getOwnerUserId(sessionId);
            if (me == null || owner == null || !me.equals(owner)) {
                throw new cn.lwx.lwxaiagent.common.BizException(403, "无权停止该会话");
            }
        }
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
