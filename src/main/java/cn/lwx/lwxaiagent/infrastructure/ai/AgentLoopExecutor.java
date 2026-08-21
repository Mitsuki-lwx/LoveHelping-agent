package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.infrastructure.orchestration.ChatExecutor;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * <h1>Agent 多步任务执行器（ADR-8 落地，2026-08-20）</h1>
 *
 * <p>基于 spring-ai-alibaba 官方 {@link ReactAgent}（内部用 StateGraph 编排
 * LLM 节点↔工具节点），取代手写 {@code BaseAgent→ReActAgent→ToolCallAgent} 循环。</p>
 *
 * <h2>保留的能力（消费者零感知）</h2>
 * <ul>
 *   <li><b>LlmGateway</b>：注入 @Primary ChatModel（主备降级/token 计量不变）；</li>
 *   <li><b>agent_task 状态机</b>：任务级可恢复由 ChatService 侧承担（ADR-3，心跳+幂等+FAILED 重提）；</li>
 *   <li><b>message 表记忆</b>：窗口历史注入（get）+ 本轮消息落库（add），MemoryChatMemory 不变；</li>
 *   <li><b>SSE 桥接</b>：文本分块模拟流式；工具调用以 "🔧" 事件提示（SRS AGENT-03 工具可视化）。</li>
 * </ul>
 *
 * <h2>checkpoint（执行断点）</h2>
 * <p>{@link RedisSaver}（graph-core 内置，Redisson 客户端）存单次 run 的线程状态；
 * 任务级可靠性仍由 MySQL {@code agent_task} 兜底（Redis 断点丢失 → 任务 FAILED 可重提）。</p>
 *
 * @see ReactAgent
 */
@Slf4j
@Component
public class AgentLoopExecutor {

    /** 循环步数上限（原 BaseAgent maxSteps=15） */
    private static final int MAX_STEPS = 15;

    /** Agent 会话上下文窗口（原 ChatMemoryFactory.createForAgent() 默认 50） */
    private static final int AGENT_WINDOW_SIZE = 50;

    private final ReactAgent agent;
    private final ChatMemoryFactory chatMemoryFactory;

    /** 活跃执行订阅（sessionId → Flux 订阅），供 stop() 取消，对应原 agent.stop() */
    private final ConcurrentHashMap<String, Disposable> activeRuns = new ConcurrentHashMap<>();

    /**
     * @param chatModel         @Primary LlmGateway（主备降级链）
     * @param toolCallbacks     全量工具（本地 4 + MCP 6，ToolRegistration 合并）
     * @param chatMemoryFactory 记忆工厂（message 表，agent 窗口 50）
     * @param redisHost         Redis 地址（checkpoint 用，Redisson 独立客户端）
     * @param redisPort         Redis 端口
     * @param redisPassword     Redis 密码（空=无认证）
     */
    public AgentLoopExecutor(ChatModel chatModel,
                             ToolCallback[] toolCallbacks,
                             ChatMemoryFactory chatMemoryFactory,
                             AgentMetricsInterceptor metricsInterceptor,
                             AgentGuardrailInterceptor guardrailInterceptor,
                             @Value("${spring.data.redis.host:localhost}") String redisHost,
                             @Value("${spring.data.redis.port:6379}") int redisPort,
                             @Value("${spring.data.redis.password:}") String redisPassword) {
        this.chatMemoryFactory = chatMemoryFactory;
        RedissonClient redisson = Redisson.create(redissonConfig(redisHost, redisPort, redisPassword));
        RedisSaver saver = RedisSaver.builder().redisson(redisson).build();
        this.agent = ReactAgent.builder()
                .name("LoveManus")
                .model(chatModel)
                .tools(toolCallbacks)
                .systemPrompt(ChatExecutor.SYSTEM_PROMPT + "\n\n" + NEXT_STEP_PROMPT)
                .saver(saver)
                .compileConfig(CompileConfig.builder().recursionLimit(MAX_STEPS).build())
                .interceptors(metricsInterceptor, guardrailInterceptor)
                .build();
        log.info("AgentLoopExecutor ready: ReactAgent(LoveManus), saver=RedisSaver, maxSteps={}, interceptors={}",
                MAX_STEPS, 2);
    }

    /**
     * <h3>执行 Agent 任务（SSE 流式）</h3>
     *
     * @param message        用户指令
     * @param conversationId 会话 ID（同时作为 Redis checkpoint 的 threadId 与 message 表归属）
     * @param onComplete     完成回调（成功 true / 失败 false+原因），对应原 BaseAgent.completionCallback
     * @return SSE 发射器
     */
    public SseEmitter run(String message, String conversationId, BiConsumer<Boolean, String> onComplete) {
        SseEmitter emitter = new SseEmitter(600_000L);
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        ChatMemory memory = chatMemoryFactory.create(AGENT_WINDOW_SIZE);

        // 1) 窗口历史注入（message 表真源）：等价原 messageList 头部插入历史
        List<Message> history = new ArrayList<>();
        try {
            List<Message> window = memory.get(conversationId);
            if (window != null) {
                history.addAll(window);
            }
        } catch (Exception e) {
            log.warn("Agent history load failed (conversation {}): {}", conversationId, e.getMessage());
        }
        history.add(new UserMessage(message));

        // 2) 本轮待落库消息（user + 聚合后的 assistant 全文；工具消息由 MessageChatMemory 过滤）
        List<Message> toPersist = new ArrayList<>();
        toPersist.add(new UserMessage(message));
        StringBuilder assistantText = new StringBuilder(); // streamMessages 为 token 级增量，需聚合

        // 3) 流式执行：Flux<Message> → SSE 桥接
        final Disposable[] holder = new Disposable[1];
        try {
            holder[0] = agent.streamMessages(history, config)
                    .doOnNext(msg -> {
                        sendToSse(emitter, msg);
                        if (msg instanceof AssistantMessage am
                                && (am.getToolCalls() == null || am.getToolCalls().isEmpty())
                                && msg.getText() != null) {
                            assistantText.append(msg.getText()); // 聚合 token → 完整回复
                        }
                    })
                    .doOnComplete(() -> {
                        activeRuns.remove(conversationId);
                        persistWithAssistant(memory, conversationId, toPersist, assistantText);
                        try {
                            emitter.send("[task-done]");
                        } catch (Exception ignored) {
                        }
                        emitter.complete();
                        onComplete.accept(true, null);
                    })
                    .doOnError(err -> {
                        activeRuns.remove(conversationId);
                        log.error("AgentLoopExecutor error (conversation {}): {}", conversationId, err.getMessage());
                        persistWithAssistant(memory, conversationId, toPersist, assistantText);
                        try {
                            emitter.send("Agent encountered an error: " + err.getMessage());
                        } catch (Exception ignored) {
                        }
                        emitter.complete();
                        onComplete.accept(false, err.getMessage());
                    })
                    .subscribe();
        } catch (com.alibaba.cloud.ai.graph.exception.GraphRunnerException e) {
            // 图构建/启动期失败：直接走错误收尾
            log.error("AgentLoopExecutor start failed (conversation {}): {}", conversationId, e.getMessage());
            try {
                emitter.send("Agent encountered an error: " + e.getMessage());
            } catch (Exception ignored) {
            }
            emitter.complete();
            onComplete.accept(false, e.getMessage());
            return emitter;
        }
        activeRuns.put(conversationId, holder[0]);
        return emitter;
    }

    /** 聚合后的 assistant 全文并入落库消息（token 级增量不逐条落库） */
    private void persistWithAssistant(ChatMemory memory, String conversationId,
                                      List<Message> toPersist, StringBuilder assistantText) {
        if (!assistantText.isEmpty()) {
            toPersist.add(new AssistantMessage(assistantText.toString()));
        }
        persist(memory, conversationId, toPersist);
    }

    /**
     * <h3>停止运行中的 Agent 执行</h3>
     * <p>取消 Flux 订阅（后台不再继续跑）；任务状态由 ChatService 侧收尾
     * （无任务标记时由 agent_task 心跳补偿扫描兜底标 FAILED）。</p>
     *
     * @param conversationId 会话 ID（threadId）
     */
    public void stop(String conversationId) {
        Disposable disposable = activeRuns.remove(conversationId);
        if (disposable != null) {
            disposable.dispose();
            log.info("AgentLoopExecutor stopped: {}", conversationId);
        }
    }

    /** 本轮消息写回 message 表（失败仅告警，不阻断主流程） */
    private void persist(ChatMemory memory, String conversationId, List<Message> messages) {
        try {
            memory.add(conversationId, messages);
        } catch (Exception e) {
            log.warn("Agent message persist failed (conversation {}): {}", conversationId, e.getMessage());
        }
    }

    /** 消息 → SSE 事件：工具调用提示 / 文本分块模拟流式 */
    private void sendToSse(SseEmitter emitter, Message msg) {
        try {
            if (msg instanceof AssistantMessage am
                    && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                // 工具调用可视化（SRS AGENT-03）：🔧 调用工具: xxx
                String names = am.getToolCalls().stream()
                        .map(tc -> tc.name() != null ? tc.name() : "unknown")
                        .distinct()
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("unknown");
                emitter.send("🔧 调用工具: " + names);
                return;
            }
            String text = msg.getText();
            if (text == null || text.isBlank()) {
                return; // 工具响应/内部消息不推送原文
            }
            for (String part : chunk(text)) {
                emitter.send(part);
            }
        } catch (Exception e) {
            log.warn("SSE send failed: {}", e.getMessage());
        }
    }

    /** 分块模拟流式输出（每块 20 字符，与 streamChatWithMedia 一致） */
    private List<String> chunk(String text) {
        List<String> parts = new ArrayList<>();
        int size = 20;
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    /** Redisson 独立客户端（不接管 Spring Data Redis） */
    private Config redissonConfig(String host, int port, String password) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(password == null || password.isBlank() ? null : password)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4);
        return config;
    }

    /** 下一步操作提示（原 LoveManus nextStepPrompt 迁移） */
    private static final String NEXT_STEP_PROMPT = """
            You have tools available — use them as needed to complete the task.
            Break complex requests into steps. When done, call the terminate tool.
            Do not list raw tool output or URLs in your thinking.
            """;
}
