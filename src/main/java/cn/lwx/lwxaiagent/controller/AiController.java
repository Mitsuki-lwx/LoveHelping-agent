package cn.lwx.lwxaiagent.controller;

import cn.lwx.lwxaiagent.common.Result;
import cn.lwx.lwxaiagent.service.ChatService;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <h1>AI 聊天控制器</h1>
 * <p>
 * 负责处理与 AI 对话相关的所有 HTTP 请求，是系统的核心控制器之一。
 * 提供了多种聊天模式的 API 接口，包括同步聊天、SSE 流式聊天、带工具调用的流式聊天、
 * RAG（检索增强生成）流式聊天，以及 LoveManus 智能体对话。
 * </p>
 *
 * <h2>URL 前缀</h2>
 * <p>该控制器没有类级别的 @RequestMapping，所有接口路径直接以 "Love_app/chat" 开头。</p>
 *
 * <h2>支持的聊天模式</h2>
 * <ul>
 *   <li><b>同步聊天</b>：一次性返回完整回复，适合简单的问答场景</li>
 *   <li><b>SSE 流式聊天</b>：通过 Server-Sent Events 逐字返回回复，提供打字机效果，提升用户体验</li>
 *   <li><b>带工具调用的流式聊天</b>：AI 可以调用外部工具（如搜索、计算等），实现更复杂的功能</li>
 *   <li><b>RAG 流式聊天</b>：结合检索增强生成，从知识库中检索相关文档后生成更准确的回复</li>
 *   <li><b>SseEmitter 流式聊天</b>：基于 Spring MVC 的 SseEmitter 实现，适用于非响应式架构</li>
 *   <li><b>LoveManus 智能体</b>：自主 AI 智能体，能够多步推理和执行复杂任务</li>
 * </ul>
 *
 * <h2>依赖服务</h2>
 * <ul>
 *   <li>{@link ChatService}：聊天服务接口，封装了所有与 AI 模型交互的核心逻辑</li>
 *   <li>{@link TenantContext}：租户上下文工具类，用于获取当前请求的租户 ID</li>
 * </ul>
 *
 * <h2>线程模型说明</h2>
 * <p>
 * 同步聊天接口在请求线程中直接执行，可能阻塞较长时间（取决于 AI 模型响应速度）。
 * SSE 流式接口使用响应式编程模型（Project Reactor 的 Flux），不会阻塞请求线程，
 * 适合高并发场景。
 * </p>
 *
 * @author lwx
 * @version 1.0
 * @see ChatService
 * @see TenantContext
 */
@RestController
public class AiController {

    /**
     * 聊天服务接口
     * <p>通过构造函数注入，封装了所有与 AI 模型交互的核心逻辑，
     * 包括同步聊天、流式聊天、工具调用、RAG 检索等功能。</p>
     */
    private final ChatService chatService;

    /**
     * 构造函数注入 ChatService
     *
     * @param chatService 聊天服务实例，由 Spring 容器自动注入
     */
    public AiController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * <h3>同步聊天接口</h3>
     * <p>
     * 发送聊天请求并同步等待 AI 返回完整回复。
     * 该接口会阻塞请求线程直到 AI 模型完成全部生成，适合对实时性要求不高的场景。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sync</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>后端服务间调用，不需要流式输出</li>
     *   <li>批量处理或定时任务场景</li>
     *   <li>需要一次性获取完整回复的简单问答</li>
     * </ul>
     *
     * <p><b>注意：</b>该接口可能会阻塞较长时间（取决于 AI 模型响应速度），
     * 建议设置合理的客户端超时时间。</p>
     *
     * @param prompt 用户输入的提示词/问题文本，不能为空
     * @param chatId 对话 ID，用于标识一次对话会话；如果为 null，服务层会创建新的对话
     * @return {@link Result} 包装的 AI 回复文本，包含操作状态和回复内容
     */
    @GetMapping("Love_app/chat/sync")
    public Result<String> chatSync(@RequestParam String prompt, @RequestParam String chatId) {
        return Result.ok(chatService.syncChat(prompt, chatId));
    }

    /**
     * <h3>带工具调用的 SSE 流式聊天接口</h3>
     * <p>
     * 以 Server-Sent Events 方式流式返回 AI 回复，并且 AI 可以在生成过程中
     * 调用外部工具（Function Calling）。工具调用结果会以特殊的 SSE 事件返回给客户端。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sse/tools</p>
     * <p><b>响应类型：</b>text/event-stream（SSE 流）</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>需要 AI 查询实时信息（如天气、股票、搜索等）</li>
     *   <li>需要 AI 执行计算、调用外部 API 等操作</li>
     *   <li>前端需要展示 AI 的工具调用过程，增强透明度和可信度</li>
     * </ul>
     *
     * @param prompt 用户输入的提示词/问题文本
     * @param chatId 对话 ID，用于维护对话上下文和记忆
     * @return {@link Flux}&lt;{@link String}&gt; 响应式流，每个元素是 AI 回复的一个文本片段
     */
    @GetMapping(value = "Love_app/chat/sse/tools", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSseWithTools(@RequestParam String prompt, @RequestParam String chatId) {
        return chatService.streamChatWithTools(prompt, chatId, TenantContext.getTenantId());
    }

    /**
     * <h3>SSE 流式聊天接口（无工具调用）</h3>
     * <p>
     * 以 Server-Sent Events 方式流式返回 AI 回复，提供打字机效果。
     * 与 {@link #chatSseWithTools(String, String)} 不同，此接口不使用工具调用功能，
     * 仅做纯文本对话生成。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sse</p>
     * <p><b>响应类型：</b>text/event-stream（SSE 流）</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>Web 端/移动端聊天界面，需要实时展示 AI 逐字回复</li>
     *   <li>纯文本对话，不需要调用外部工具</li>
     *   <li>需要良好用户体验的通用聊天场景</li>
     * </ul>
     *
     * <p><b>注意：</b>此方法签名与 {@link #chatSseServer(String, String)} 相同（路径和参数完全一致），
     * 只有返回类型不同。Spring MVC 会根据客户端请求的 Accept 头或路由匹配优先级来选择具体调用哪个方法。
     * 如出现歧义，可能需要在其中一个方法上使用更具体的路径或条件注解来区分。</p>
     *
     * @param prompt 用户输入的提示词/问题文本
     * @param chatId 对话 ID，用于维护对话上下文和记忆
     * @return {@link Flux}&lt;{@link String}&gt; 响应式流，每个元素是 AI 回复的一个文本片段
     */
    @GetMapping(value = "Love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSse(@RequestParam String prompt, @RequestParam String chatId,
                                @RequestParam(required = false) String mediaIds) {
        if (mediaIds != null && !mediaIds.isBlank()) {
            List<Long> ids = java.util.Arrays.stream(mediaIds.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong).toList();
            return chatService.streamChatWithMedia(prompt, chatId, ids, TenantContext.getTenantId());
        }
        return chatService.streamChat(prompt, chatId, TenantContext.getTenantId());
    }

    /**
     * <h3>SSE 流式聊天接口（返回 ServerSentEvent 格式）</h3>
     * <p>
     * 与 {@link #chatSse(String, String)} 功能类似，但返回类型为
     * {@link ServerSentEvent}{@code <String>}，允许对 SSE 事件的 id、event 类型和 comment
     * 等元数据进行更精细的控制。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sse</p>
     * <p><b>响应类型：</b>text/event-stream（SSE 流）</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>前端需要对不同类型的 SSE 事件做区分处理（如区分"文本片段"和"错误"事件）</li>
     *   <li>需要自定义 SSE 事件的元数据（如事件 ID 用于断点续传）</li>
     * </ul>
     *
     * <p><b>注意：</b>此方法与 {@link #chatSse(String, String)} 的 URL 路径和参数完全相同，
     * 存在路由歧义。Spring 框架会根据返回类型和请求的 Accept 头来决定调用哪个方法。
     * 建议后续重构时通过不同的路径或条件路由来消除歧义。</p>
     *
     * @param prompt 用户输入的提示词/问题文本
     * @param chatId 对话 ID，用于维护对话上下文和记忆
     * @return {@link Flux}{@code <}{@link ServerSentEvent}{@code <String>>} 响应式流，
     *         每个元素是一个完整的 SSE 事件对象，包含 data 字段
     */
    @GetMapping(value = "Love_app/chat/sse")
    public Flux<ServerSentEvent<String>> chatSseServer(@RequestParam String prompt, @RequestParam String chatId,
                                                       @RequestParam(required = false) String mediaIds) {
        Flux<String> stream;
        if (mediaIds != null && !mediaIds.isBlank()) {
            List<Long> ids = java.util.Arrays.stream(mediaIds.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong).toList();
            stream = chatService.streamChatWithMedia(prompt, chatId, ids, TenantContext.getTenantId());
        } else {
            stream = chatService.streamChat(prompt, chatId, TenantContext.getTenantId());
        }
        return stream
                // 将原始的字符串片段包装成 ServerSentEvent 对象
                // ServerSentEvent 提供了更丰富的 SSE 协议支持（如事件 ID、事件类型等）
                .map(trunk -> ServerSentEvent.<String>builder(trunk).data(trunk).build());
    }

    /**
     * <h3>基于 SseEmitter 的流式聊天接口</h3>
     * <p>
     * 使用 Spring MVC 原生的 {@link SseEmitter} 实现 SSE 流式聊天。
     * 与基于 WebFlux 的 {@link Flux} 方案不同，SseEmitter 基于传统的 Servlet 异步机制，
     * 更适合在 Spring MVC（非 WebFlux）架构下使用。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sse_emitter</p>
     *
     * <p><b>工作原理：</b></p>
     * <ol>
     *   <li>创建 SseEmitter 对象并立即返回给客户端（不阻塞请求线程）</li>
     *   <li>订阅 Flux 流，每当有新的文本片段到达时，通过 emitter.send() 发送给客户端</li>
     *   <li>当 Flux 流正常完成时，调用 emitter.complete() 关闭连接</li>
     *   <li>当 Flux 流出错时，调用 emitter.completeWithError() 通知客户端</li>
     * </ol>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>Spring MVC 架构下需要 SSE 流式输出</li>
     *   <li>不需要引入 WebFlux 依赖的项目</li>
     * </ul>
     *
     * @param prompt 用户输入的提示词/问题文本
     * @param chatId 对话 ID，用于维护对话上下文和记忆
     * @return {@link SseEmitter} SSE 发射器，负责管理客户端连接的整个生命周期
     */
    @GetMapping(value = "Love_app/chat/sse_emitter")
    public SseEmitter chatSseEmitter(@RequestParam String prompt, @RequestParam String chatId) {
        // 创建 SseEmitter，默认超时时间由 Spring 配置决定
        SseEmitter emitter = new SseEmitter();
        // 订阅响应式流，通过 SseEmitter 桥接到 Servlet 异步机制
        chatService.streamChat(prompt, chatId, TenantContext.getTenantId())
                .subscribe(
                        // onNext：处理每个文本片段，发送给客户端
                        trunk -> {
                            try {
                                emitter.send(trunk);
                            } catch (Exception e) {
                                // 发送失败时通知客户端发生错误
                                emitter.completeWithError(e);
                            }
                        },
                        // onError：流处理过程中发生异常
                        error -> emitter.completeWithError(error),
                        // onComplete：流正常结束，关闭 SSE 连接
                        () -> emitter.complete()
                );
        return emitter;
    }

    /**
     * <h3>带 RAG（检索增强生成）的 SSE 流式聊天接口</h3>
     * <p>
     * 结合检索增强生成技术，在生成回复前先从知识库中检索与用户问题相关的文档，
     * 然后将检索结果作为上下文注入到 AI 模型中，从而生成更准确、更有依据的回复。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/sse/rag</p>
     * <p><b>响应类型：</b>text/event-stream（SSE 流）</p>
     *
     * <p><b>RAG 工作流程：</b></p>
     * <ol>
     *   <li>接收用户问题</li>
     *   <li>将问题向量化，在向量数据库中进行相似度检索</li>
     *   <li>获取 Top-K 相关文档片段</li>
     *   <li>将检索到的文档作为上下文与用户问题一起发送给 AI 模型</li>
     *   <li>AI 基于提供的上下文生成更准确的回复</li>
     * </ol>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>基于私有知识库的智能问答</li>
     *   <li>企业文档助手、客服知识库等需要引用特定资料的场景</li>
     *   <li>减少 AI "幻觉"（hallucination），提高回复的事实准确性</li>
     * </ul>
     *
     * @param prompt 用户输入的提示词/问题文本
     * @param chatId 对话 ID，用于维护对话上下文和记忆
     * @return {@link Flux}&lt;{@link String}&gt; 响应式流，每个元素是 AI 回复的一个文本片段
     */
    @GetMapping(value = "Love_app/chat/sse/rag", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatSseWithRAG(@RequestParam String prompt, @RequestParam String chatId) {
        return chatService.streamChatWithRAG(prompt, chatId, TenantContext.getTenantId());
    }

    /**
     * <h3>LoveManus 智能体对话接口</h3>
     * <p>
     * 启动一个自主 AI 智能体（Agent）来处理用户任务。与普通聊天不同，
     * 智能体可以自主进行多步推理、调用工具、制定计划并执行复杂任务。
     * 通过 SSE 流实时返回智能体的思考过程和执行结果。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/LoveManus</p>
     *
     * <p><b>智能体能力：</b></p>
     * <ul>
     *   <li>自主规划和分解复杂任务</li>
     *   <li>多步推理和决策</li>
     *   <li>调用外部工具（搜索、计算、代码执行等）</li>
     *   <li>根据执行结果动态调整计划</li>
     * </ul>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>复杂的多步骤任务（如"帮我分析这份数据并生成报告"）</li>
     *   <li>需要 AI 自主探索和决策的开放式问题</li>
     *   <li>需要调用多个外部工具协同完成的任务</li>
     * </ul>
     *
     * <p><b>注意：</b>智能体会话可能持续较长时间，客户端需要保持 SSE 连接。
     * 如果中途需要取消，可以调用 {@link #stopLoveManus(String)} 接口。</p>
     *
     * @param message  用户发送给智能体的任务描述或消息
     * @param sessionId 会话 ID，用于标识一次智能体会话；客户端可通过响应头 X-Session-Id 获取
     * @param response HTTP 响应对象，用于设置响应头（将 sessionId 通过 X-Session-Id 头返回给客户端）
     * @return {@link SseEmitter} SSE 发射器，实时推送智能体的思考过程和执行结果
     */
    @GetMapping(value = "Love_app/chat/LoveManus")
    public SseEmitter doChatWithLoveManus(@RequestParam String message, String sessionId,
                                          @RequestParam(required = false) String idempotencyKey,
                                          HttpServletResponse response) {
        // 启动智能体会话，返回 SseEmitter 用于实时推送智能体状态（任务已落库，ADR-3）
        SseEmitter emitter = chatService.agentChat(message, sessionId, idempotencyKey);
        // 将 sessionId 通过响应头返回给客户端，便于客户端后续调用停止接口
        response.setHeader("X-Session-Id", sessionId);
        return emitter;
    }

    /**
     * 查询 Agent 任务状态（ADR-3）：崩溃后前端可轮询确认任务是否 FAILED（可重提）。
     */
    @GetMapping("Love_app/chat/LoveManus/task/{taskId}")
    public Result<cn.lwx.lwxaiagent.entity.AgentTask> getAgentTask(@PathVariable Long taskId) {
        return Result.ok(chatService.getAgentTask(taskId));
    }

    /**
     * <h3>停止 LoveManus 智能体会话</h3>
     * <p>
     * 主动停止正在运行的智能体会话。当用户希望中断智能体的执行时调用此接口。
     * </p>
     *
     * <p><b>HTTP 方法：</b>GET</p>
     * <p><b>请求路径：</b>/Love_app/chat/LoveManus/stop/{sessionId}</p>
     *
     * <p><b>使用场景：</b></p>
     * <ul>
     *   <li>用户主动取消正在执行的任务</li>
     *   <li>智能体陷入循环或执行时间过长，需要人工干预</li>
     *   <li>前端页面关闭或用户离开时清理会话资源</li>
     * </ul>
     *
     * @param sessionId 要停止的智能体会话 ID（路径变量），由 {@link #doChatWithLoveManus(String, String, HttpServletResponse)} 返回
     * @return {@link Result}&lt;{@link String}&gt; 操作结果，包含停止状态信息
     */
    @GetMapping("Love_app/chat/LoveManus/stop/{sessionId}")
    public Result<String> stopLoveManus(@PathVariable String sessionId) {
        return Result.ok(chatService.stopAgent(sessionId));
    }
}
