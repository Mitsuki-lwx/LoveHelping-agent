package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.constant.FileConstant;
import cn.lwx.lwxaiagent.entity.AgentTask;
import cn.lwx.lwxaiagent.entity.MessageMedia;
import cn.lwx.lwxaiagent.evolution.SessionTracker;
import cn.lwx.lwxaiagent.evolution.SkillRetriever;
import cn.lwx.lwxaiagent.mapper.MessageMediaMapper;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.memory.MemoryStore;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>核心聊天服务</h1>
 * <p>
 * 本类是整个 AI Agent 系统的聊天业务编排层，负责协调底层 AI 引擎（{@link LoveApp}）、
 * 技能检索、会话跟踪、多租户上下文、速率限制等多个子系统，对外提供统一的聊天接口。
 * </p>
 *
 * <h2>支持的聊天模式</h2>
 * <ul>
 *   <li><b>同步聊天（syncChat）</b>：阻塞式调用，等待 AI 完整回复后一次性返回。</li>
 *   <li><b>流式聊天（streamChat）</b>：基于 Reactor {@link Flux} 的异步流式响应，
 *       适用于需要逐字展示 AI 回复的场景。</li>
 *   <li><b>带工具调用的流式聊天（streamChatWithTools）</b>：在流式响应基础上，
 *       允许 AI 调用已注册的工具（{@link ToolCallback}）来完成任务。</li>
 *   <li><b>带 RAG 检索增强的流式聊天（streamChatWithRAG）</b>：在流式响应基础上，
 *       结合知识库检索增强生成（Retrieval-Augmented Generation）。</li>
 *   <li><b>Agent 会话（agentChat）</b>：基于 {@link SseEmitter} 的 Server-Sent Events 模式，
 *       支持多轮对话和主动停止，适合长连接场景。</li>
 * </ul>
 *
 * <h2>核心流程</h2>
 * <ol>
 *   <li>从 {@link TenantContext} 获取当前租户 ID，若为空则使用默认值 "default"。</li>
 *   <li>通过 {@link RateLimiter} 检查该租户的当日配额是否耗尽。</li>
 *   <li>通过 {@link SkillRetriever} 检索与当前提示词相关的技能上下文，
 *       将其注入到提示词中，增强 AI 的领域能力。</li>
 *   <li>调用底层 {@link LoveApp} 引擎执行实际聊天逻辑。</li>
 *   <li>在请求完成时（无论成功或失败），递增速率计数器并记录会话追踪信息。</li>
 * </ol>
 *
 * <h2>多租户支持</h2>
 * <p>
 * 所有方法均从 {@link TenantContext}（基于 {@link ThreadLocal} 的租户上下文）获取租户 ID，
 * 确保不同租户的数据和配额完全隔离。
 * </p>
 *
 * <h2>并发安全</h2>
 * <p>
 * 使用 {@link ConcurrentHashMap} 管理活跃的 Agent 会话，保证高并发场景下的线程安全。
 * </p>
 *
 * @author lwx-ai-agent
 * @since 1.0
 * @see LoveApp   底层 AI 引擎
 * @see RateLimiter 速率限制器
 * @see SkillRetriever 技能检索器
 * @see SessionTracker 会话跟踪器
 */
@Slf4j
@Service
public class ChatService {

    /**
     * 底层 AI 引擎，封装了对大语言模型（LLM）的调用逻辑，
     * 包括提示词构建、上下文管理、流式响应等。
     */

    /**
     * 会话跟踪器，用于记录每个会话的消息发送情况，
     * 为后续的进化学习和统计分析提供数据基础。
     */
    private final SessionTracker sessionTracker;

    /**
     * 技能检索器，根据用户输入的提示词从知识库中检索相关的技能上下文，
     * 将检索结果注入提示词以增强 AI 的领域表现。
     */
    private final SkillRetriever skillRetriever;

    /**
     * Spring AI 工具回调数组，包含所有已注册的工具。
     * AI 可以在对话过程中调用这些工具来执行特定操作
     * （如网络搜索、文件操作、数据库查询等）。
     */
    private final ToolCallback[] toolCallbacks;

    /**
     * Spring AI 的聊天模型接口，封装了与大语言模型的底层通信。
     */
    private final ChatModel chatModel;

    /**
     * 聊天记忆工厂，用于为 Agent 会话创建独立的对话记忆实例，
     * 支持 Agent 在多轮对话中保持上下文连贯性。
     */
    private final ChatMemoryFactory chatMemoryFactory;

    /**
     * 速率限制器，基于 Redis 实现每日配额控制，
     * 防止单个租户过度使用 API 资源。
     */
    private final RateLimiter rateLimiter;

    /**
     * 视觉模型（ADR-11，可选）：基于 GoPlan 端点 + mimo-v2.5；未配置时为 null。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.beans.factory.annotation.Qualifier("visionChatModel")
    private ChatModel visionChatModel;

    /**
     * 视觉端口（ADR-17）：统一视觉调用抽象，屏蔽底层协议（当前 OpenAI 兼容手写实现，
     * 未来 Anthropic 等只需新实现 VisionPort）。未配置时为 null（图片聊天不可用）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.lwx.lwxaiagent.infrastructure.ai.VisionPort visionPort;

    /**
     * 图片附件 Mapper（ADR-11）：含图聊天时读取/校验媒体。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private MessageMediaMapper messageMediaMapper;

    /**
     * 护栏规则引擎（ADR-6）：业务层显式检查（多层防御——advisor 链因框架机制未生效，
     * 业务层是当前护栏的实际执行点，advisor 层随 loop 重构修复）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRuleService;

    @org.springframework.beans.factory.annotation.Autowired
    private cn.lwx.lwxaiagent.mapper.GuardrailEventMapper guardrailEventMapper;

    /**
     * Micrometer 指标注册表（08 §2.2）：chat.request / guardrail.trigger 等计数器。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 上报聊天请求计数（08 §2.2 chat.request{mode}）。 */
    private void recordChatRequest(String mode) {
        try {
            meterRegistry.counter("chat.request", "mode", mode).increment();
        } catch (Exception e) {
            // 指标上报失败不影响业务
        }
    }

    /**
     * 业务层护栏检查（ADR-6）：L3 抛 BizException（自伤→转介文案）；L1/L2 记录事件不阻断。
     */
    private void guardrailCheck(String prompt) {
        var verdict = guardrailRuleService.check(prompt);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked ({}): {}", verdict.ruleId(), prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            recordGuardrailEvent(prompt, verdict, "BLOCKED");
            String fallback = "self_harm".equals(verdict.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。你不需要独自面对，我们慢慢聊。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
            throw new cn.lwx.lwxaiagent.common.BizException(4001, fallback);
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            recordGuardrailEvent(prompt, verdict, "LOGGED");
        }
    }

    private void recordGuardrailEvent(String prompt, cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService.Verdict verdict, String action) {
        try {
            cn.lwx.lwxaiagent.entity.GuardrailEvent event = new cn.lwx.lwxaiagent.entity.GuardrailEvent();
            event.setUserId(TenantContext.getUserId());
            event.setLevel(verdict.level());
            event.setRuleId(verdict.ruleId());
            event.setContentHmac(sha256(prompt));
            event.setAction(action);
            event.setCreatedAt(java.time.LocalDateTime.now());
            guardrailEventMapper.insert(event);
            try {
                meterRegistry.counter("guardrail.trigger",
                        "level", String.valueOf(verdict.level()),
                        "rule_id", verdict.ruleId()).increment();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.warn("Failed to record guardrail event: {}", e.getMessage());
        }
    }

    private String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Agent 任务服务（ADR-3）：任务生命周期落库 + 崩溃补偿。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.lwx.lwxaiagent.service.AgentTaskService agentTaskService;

    /**
     * 查询 Agent 任务状态（ADR-3）。
     */
    public cn.lwx.lwxaiagent.entity.AgentTask getAgentTask(Long taskId) {
        return agentTaskService.get(taskId);
    }

    /**
     * 记忆存储（ADR-14）：跨会话注入用户档案与摘要。
     */
    private final MemoryStore memoryStore;

    /**
     * 活跃的 Agent 会话映射表，键为会话 ID（sessionId），值为该会话的 SseEmitter。
     * <p>
     * 作用：
     * <ul>
     *   <li>支持通过 sessionId 查找并停止正在运行的 Agent（ADR-8：ReactAgent 执行核心）</li>
     *   <li>在会话超时或异常时自动清理</li>
     * </ul>
     */
    private final ConcurrentHashMap<String, SseEmitter> activeSessions = new ConcurrentHashMap<>();

    /**
     * Agent 多步任务执行核心（ADR-8，2026-08-20）：spring-ai-alibaba ReactAgent，
     * 取代手写 LoveManus 循环。任务状态流转由 run() 的完成回调驱动。
     */
    @org.springframework.beans.factory.annotation.Autowired
    private cn.lwx.lwxaiagent.infrastructure.ai.AgentLoopExecutor agentLoopExecutor;

    /**
     * <h3>构造函数 - 依赖注入</h3>
     * <p>
     * 通过 Spring 的构造器注入方式接收所有依赖组件。
     * 构造器注入相较于字段注入（@Autowired）的优势：
     * <ul>
     *   <li>依赖不可变（final 字段），保证线程安全</li>
     *   <li>便于单元测试（可以直接传入 mock 对象）</li>
     *   <li>避免循环依赖问题</li>
     * </ul>
     * </p>
     *
     * @param loveApp          底层 AI 引擎，处理实际的 LLM 调用
     * @param sessionTracker   会话跟踪器，记录消息发送统计
     * @param skillRetriever   技能检索器，为提示词增强领域知识
     * @param toolCallbacks    已注册的 AI 工具回调列表
     * @param chatModel        LLM 聊天模型接口
     * @param chatMemoryFactory 对话记忆工厂，创建 Agent 记忆实例
     * @param rateLimiter      速率限制器，控制每日 API 配额
     */
    public ChatService(SessionTracker sessionTracker,
                       SkillRetriever skillRetriever,
                       ToolCallback[] toolCallbacks, ChatModel chatModel,
                       ChatMemoryFactory chatMemoryFactory,
                       RateLimiter rateLimiter, MemoryStore memoryStore,
                       cn.lwx.lwxaiagent.harness.governance.GuardrailAdvisor guardrailAdvisor,
                       cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever parentChildDocumentRetriever,
                       cn.lwx.lwxaiagent.rag.QueryRewriter queryRewriter) {
        this.sessionTracker = sessionTracker;
        this.skillRetriever = skillRetriever;
        this.toolCallbacks = toolCallbacks;
        this.chatModel = chatModel;
        this.chatMemoryFactory = chatMemoryFactory;
        this.rateLimiter = rateLimiter;
        this.memoryStore = memoryStore;
        this.parentChildDocumentRetriever = parentChildDocumentRetriever;
        this.queryRewriter = queryRewriter;
        // 统一 ChatClient（接管原 LoveApp 职责，2026-08-19）
        this.chatMemory = chatMemoryFactory.create();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new cn.lwx.lwxaiagent.harness.MyLoggerAdvisor(),
                        guardrailAdvisor)
                .build();
    }

    /** 恋爱军师系统提示词（原 LoveApp 迁移） */
    private static final String SYSTEM_PROMPT = """
            You are a seasoned love and relationship psychology expert.
            Introduce yourself at the start so the user knows they can confide in you.

            Categorize your approach by relationship status:
            - Single: Ask about social circle expansion and challenges in pursuing someone they're interested in.
            - Dating: Ask about communication issues, personality clashes, and conflicts arising from different habits.
            - Married: Ask about family responsibilities and in-law relationship management.

            Guide the user to describe the full story — what happened, how the other party reacted,
            and their own thoughts — before offering tailored advice.

            Add occasional emojis (💕🌸✨💝🌹) to make replies warm and engaging.

            【Counter-Question Principle】If the user's question is vague or lacks key details
            (e.g. "she's mad at me", "how to date a girl", "we had a fight"), do NOT give advice
            right away. Ask 2-3 clarifying questions first. Once you have enough information, provide
            specific, actionable suggestions. Focus your questions on: what happened, the current
            relationship stage, what the user has already tried, and the other person's reactions.

            IMPORTANT: Always respond in the same language as the user's message.
            """;

    /** 统一 ChatClient（原 LoveApp 职责） */
    private final org.springframework.ai.chat.client.ChatClient chatClient;
    private final org.springframework.ai.chat.memory.ChatMemory chatMemory;
    private final cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever parentChildDocumentRetriever;
    private final cn.lwx.lwxaiagent.rag.QueryRewriter queryRewriter;
    @org.springframework.beans.factory.annotation.Value("${app.rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    /** RAG Advisor（原 LoveApp 逻辑）：父子索引检索 + 查询改写 */
    private org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor ragAdvisor() {
        return org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor.builder()
                .documentRetriever(parentChildDocumentRetriever)
                .queryTransformers(queryRewriteEnabled
                        ? new org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer[]{queryRewriter}
                        : new org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer[0])
                .build();
    }

    /** 限流用户键（ADR-13：按用户限流；未登录用 anonymous） */
    private String currentUserId() {
        String uid = TenantContext.getUserId();
        return uid != null && !uid.isBlank() ? uid : "anonymous";
    }

    /**
     * 组装 System 上下文：用户记忆（ADR-14，跨会话）在前，领域技能在后。
     * 在请求线程同步取 userId（避免异步线程 ThreadLocal 失效）。
     */
    private String assembleContext(String prompt, String tenantId) {
        String memoryContext = memoryStore.retrieveAsContext(TenantContext.getUserId());
        String skillContext = skillRetriever.retrieveAsContext(prompt, tenantId);
        if (memoryContext.isEmpty()) {
            return skillContext;
        }
        return memoryContext + skillContext;
    }

    /**
     * <h3>同步聊天</h3>
     * <p>
     * 阻塞式调用底层 AI 引擎，等待完整回复后一次性返回。
     * 适用于对实时性要求不高、希望简化调用逻辑的场景。
     * </p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li>从 {@link TenantContext} 获取租户 ID，若为空则使用 "default"</li>
     *   <li>检查该租户的当日 API 配额是否耗尽（通过 {@link RateLimiter}）</li>
     *   <li>根据用户输入的提示词检索相关的技能上下文</li>
     *   <li>调用 {@link LoveApp#doChat(String, String, String)} 执行聊天</li>
     *   <li>递增速率计数器</li>
     *   <li>记录会话消息发送事件</li>
     * </ol>
     *
     * @param prompt 用户输入的提示词/问题文本，不能为空
     * @param chatId 会话 ID，用于标识一次对话会话，
     *               支持多轮对话时将同一会话的消息关联在一起
     * @return AI 生成的完整回复文本
     * @throws cn.lwx.lwxaiagent.common.BizException 当每日 API 配额耗尽时抛出（HTTP 状态码 429）
     */
    public String syncChat(String prompt, String chatId) {
        String tenantId = TenantContext.getTenantId();
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(currentUserId());
        guardrailCheck(prompt);
        recordChatRequest("sync");
        String skillContext = skillRetriever.retrieveAsContext(prompt, tid);
        log.info("syncChat: tenant={}, skillContext={}", tid, skillContext.isEmpty() ? "(none)" : skillContext);
        var req = chatClient.prompt().user(prompt)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId));
        if (skillContext != null && !skillContext.isEmpty()) {
            req.system(SYSTEM_PROMPT + skillContext);
        }
        String result = req.call().content();
        rateLimiter.increment(currentUserId());
        sessionTracker.onMessageSent(chatId, tid);
        return result;
    }

    /**
     * <h3>流式聊天（基础版）</h3>
     * <p>
     * 基于 Reactor {@link Flux} 的异步流式响应。AI 每生成一个片段（token），
     * 就立即通过流推送给客户端，实现"逐字输出"效果，大幅提升用户体验。
     * </p>
     *
     * <h4>与 syncChat 的区别</h4>
     * <ul>
     *   <li><b>返回类型</b>：返回 {@link Flux}&lt;{@link String}&gt; 而非普通 String，
     *       支持响应式流处理</li>
     *   <li><b>配额递增时机</b>：通过 {@link Flux#doFinally(java.util.function.Consumer)}
     *       在流完成时（无论正常完成、取消还是异常）执行递增，而非在方法返回前</li>
     * </ul>
     *
     * @param prompt   用户输入的提示词/问题文本，不能为空
     * @param chatId   会话 ID，用于标识一次对话会话
     * @param tenantId 租户 ID，用于多租户隔离；
     *                 若为 {@code null} 或空字符串，则使用默认值 "default"
     * @return {@link Flux}&lt;{@link String}&gt; 响应式字符串流，
     *         每个元素为 AI 生成的一个文本片段
     * @throws cn.lwx.lwxaiagent.common.BizException 当每日 API 配额耗尽时抛出
     */
    public Flux<String> streamChat(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(currentUserId());
        guardrailCheck(prompt);
        recordChatRequest("stream");
        String skillContext = assembleContext(prompt, tid);
        var req = chatClient.prompt().user(prompt)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId));
        if (skillContext != null && !skillContext.isEmpty()) {
            req.system(SYSTEM_PROMPT + skillContext);
        }
        return req.stream().content()
                .doFinally(sig -> {
                    rateLimiter.increment(currentUserId());
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    /**
     * <h3>带工具调用的流式聊天</h3>
     * <p>
     * 在流式响应的基础上，允许 AI 调用预注册的工具（{@link ToolCallback}）来完成复杂任务。
     * 例如：AI 可以调用搜索引擎工具获取最新信息，或调用计算器工具进行数学运算。
     * </p>
     *
     * <h4>工具调用机制</h4>
     * <p>
     * 当 AI 判断需要调用某个工具时，会暂停文本生成，转而输出工具调用请求；
     * 系统执行工具后，将结果反馈给 AI，AI 再基于结果继续生成回复。
     * 这个过程对终端用户是透明的（用户通常看不到中间的工具调用过程）。
     * </p>
     *
     * @param prompt   用户输入的提示词/问题文本，不能为空
     * @param chatId   会话 ID，用于标识一次对话会话
     * @param tenantId 租户 ID，用于多租户隔离；
     *                 若为 {@code null} 或空字符串，则使用默认值 "default"
     * @return {@link Flux}&lt;{@link String}&gt; 响应式字符串流
     * @throws cn.lwx.lwxaiagent.common.BizException 当每日 API 配额耗尽时抛出
     */
    public Flux<String> streamChatWithTools(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(currentUserId());
        guardrailCheck(prompt);
        recordChatRequest("tools");
        String skillContext = assembleContext(prompt, tid);
        var req = chatClient.prompt().user(prompt)
                .advisors(new cn.lwx.lwxaiagent.harness.MyLoggerAdvisor())
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId))
                .toolCallbacks(toolCallbacks);
        if (skillContext != null && !skillContext.isEmpty()) {
            req.system(SYSTEM_PROMPT + skillContext);
        }
        return req.stream().content()
                .doFinally(sig -> {
                    rateLimiter.increment(currentUserId());
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    /**
     * <h3>带 RAG 检索增强的流式聊天</h3>
     * <p>
     * 在流式响应的基础上，结合 RAG（Retrieval-Augmented Generation，检索增强生成）技术。
     * AI 在生成回复前，先到知识库中检索与用户问题相关的文档片段，
     * 将这些片段作为额外的上下文注入到提示词中，从而提高回复的准确性和相关性。
     * </p>
     *
     * <h4>RAG 的优势</h4>
     * <ul>
     *   <li>减少 AI 的"幻觉"（hallucination），回答更基于事实</li>
     *   <li>能够引用最新的知识库内容，不受模型训练截止日期限制</li>
     *   <li>适用于企业内部知识库问答场景</li>
     * </ul>
     *
     * @param prompt   用户输入的提示词/问题文本，不能为空
     * @param chatId   会话 ID，用于标识一次对话会话
     * @param tenantId 租户 ID，用于多租户隔离；
     *                 若为 {@code null} 或空字符串，则使用默认值 "default"
     * @return {@link Flux}&lt;{@link String}&gt; 响应式字符串流
     * @throws cn.lwx.lwxaiagent.common.BizException 当每日 API 配额耗尽时抛出
     */
    public Flux<String> streamChatWithRAG(String prompt, String chatId, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(currentUserId());
        guardrailCheck(prompt);
        recordChatRequest("rag");
        String skillContext = assembleContext(prompt, tid);
        var req = chatClient.prompt().user(prompt)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId))
                .advisors(ragAdvisor());
        if (skillContext != null && !skillContext.isEmpty()) {
            req.system(SYSTEM_PROMPT + skillContext);
        }
        return req.stream().content()
                .doFinally(sig -> {
                    rateLimiter.increment(currentUserId());
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    /**
     * 含图聊天（ADR-11）：mediaIds 非空时走视觉模型（mimo-v2.5），
     * 图片以 Media 注入 UserMessage；归属校验只允许访问自己的图片。
     */
    public Flux<String> streamChatWithMedia(String prompt, String chatId, List<Long> mediaIds, String tenantId) {
        String tid = (tenantId != null) ? tenantId : "default";
        rateLimiter.checkQuota(currentUserId());
        guardrailCheck(prompt);
        recordChatRequest("media");
        if (visionPort == null) {
            throw new BizException(503, "图片理解暂不可用");
        }
        MediaPayload payload = loadMedia(mediaIds);
        if (payload.images.isEmpty()) {
            throw new BizException(400, "图片不存在或无权访问");
        }
        String skillContext = assembleContext(prompt, tid);
        String systemPrompt = """
                你是恋爱军师，请分析用户提供的图片（聊天截图、照片等）：
                - 若是聊天截图：解读对方语气、意图，给出建议（不替用户做决定）
                - 尊重隐私：不评价图片中无关人员的外貌
                - 输出简洁、共情、可执行
                """ + (skillContext == null ? "" : skillContext);
        String fullPrompt = systemPrompt + "\n\n用户问题：" + prompt;

        // 视觉调用（同步拿全文，按块包装为流式输出）
        String answer = visionPort.chat(fullPrompt, payload.images, payload.mime);
        return Flux.fromIterable(chunk(answer))
                .doFinally(sig -> {
                    rateLimiter.increment(currentUserId());
                    sessionTracker.onMessageSent(chatId, tid);
                });
    }

    /** 将长文本切块用于模拟流式输出 */
    private List<String> chunk(String text) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        int size = 20;
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }

    private record MediaPayload(List<byte[]> images, String mime) {}

    private MediaPayload loadMedia(List<Long> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return new MediaPayload(List.of(), "image/jpeg");
        }
        String userId = TenantContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        List<byte[]> images = new ArrayList<>();
        String mime = "image/jpeg";
        for (Long id : mediaIds) {
            MessageMedia media = messageMediaMapper.selectById(id);
            if (media == null || !userId.equals(media.getUserId())) {
                throw new BizException(403, "无权访问该图片");
            }
            if (!"PENDING".equals(media.getStatus()) && !"USED".equals(media.getStatus())) {
                throw new BizException(400, "图片状态异常");
            }
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(FileConstant.FILE_SAVE_DIR, "uploads", media.getObjectKey()));
                mime = switch (media.getMediaType()) {
                    case "PNG" -> "image/png";
                    case "WEBP" -> "image/webp";
                    default -> "image/jpeg";
                };
                images.add(bytes);
            } catch (java.io.IOException e) {
                throw new BizException(500, "图片读取失败");
            }
            if (images.size() >= 4) {
                break;
            }
        }
        return new MediaPayload(images, mime);
    }

    /**
     * <p>
     * 使用 {@link SseEmitter}（Server-Sent Events）协议进行长连接通信。
     * 与流式聊天的核心区别在于：Agent 模式维护了完整的对话状态和工具调用能力，
     * 支持多轮对话，并且可以在对话过程中主动"思考"和"行动"。
     * </p>
     *
     * <h4>会话生命周期管理（ADR-8：ReactAgent 执行核心）</h4>
     * <ol>
     *   <li><b>执行</b>：sessionId（即 checkpoint threadId + message 表归属）提交给
     *       {@link cn.lwx.lwxaiagent.infrastructure.ai.AgentLoopExecutor}，内部 ReactAgent 循环执行</li>
     *   <li><b>收尾</b>：流完成 → 完成回调驱动 agent_task 流转（SUCCESS/FAILED）；SSE 超时/错误 → FAILED</li>
     *   <li><b>销毁</b>：在 SSE 超时/错误/完成时从 activeSessions 中移除，并记录会话跟踪信息</li>
     * </ol>
     *
     * <h4>SSE vs WebSocket 的选择</h4>
     * <p>
     * 本方法使用 SSE 而非 WebSocket，原因如下：
     * <ul>
     *   <li>SSE 是单向通信（服务器到客户端），符合 AI 对话的通信模式</li>
     *   <li>SSE 基于 HTTP 协议，穿透代理和防火墙更友好</li>
     *   <li>SSE 自动支持重连机制</li>
     * </ul>
     * </p>
     *
     * @param message   用户发送的消息文本
     * @param sessionId 会话 ID。若为 {@code null} 或空字符串，
     *                  系统会自动生成一个 UUID 作为新的会话 ID
     * @return {@link SseEmitter} 实例，用于向客户端推送 SSE 事件流
     */
    public SseEmitter agentChat(String message, String sessionId, String idempotencyKey) {
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";
        // 任务落库（ADR-3）：幂等键存在则复用；任务 ID 作为 Agent 会话 ID
        AgentTask task = agentTaskService.submit(userId, message, idempotencyKey);
        Long taskId = task.getId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = String.valueOf(taskId);
        }
        String finalSessionId = sessionId;
        String tenantId = TenantContext.getTenantId();
        String tid = (tenantId != null) ? tenantId : "default";

        // 护栏（ADR-6）：Agent 入口与聊天入口同级别 L3 阻断
        guardrailCheck(message);
        recordChatRequest("agent");

        agentTaskService.start(taskId); // PENDING → RUNNING（含心跳）

        // ADR-8（2026-08-20）：ReactAgent 执行核心——完成回调驱动任务状态流转
        SseEmitter emitter = agentLoopExecutor.run(message, finalSessionId, (ok, err) -> {
            if (Boolean.TRUE.equals(ok)) {
                agentTaskService.succeed(taskId, null, 0L);
            } else {
                agentTaskService.fail(taskId, "ERROR", err);
            }
            sessionTracker.onMessageSent(finalSessionId, tid);
        });
        activeSessions.put(finalSessionId, emitter);
        emitter.onTimeout(() -> {
            log.info("AgentTask[{}] emitter onTimeout", taskId);
            agentTaskService.fail(taskId, "TIMEOUT", "Agent 执行超时");
            activeSessions.remove(finalSessionId);
            sessionTracker.onMessageSent(finalSessionId, tid);
        });
        emitter.onError(e -> {
            log.info("AgentTask[{}] emitter onError: {}", taskId, e.getMessage());
            agentTaskService.fail(taskId, "ERROR", e.getMessage());
            activeSessions.remove(finalSessionId);
            sessionTracker.onMessageSent(finalSessionId, tid);
        });
        emitter.onCompletion(() -> {
            log.info("AgentTask[{}] emitter onCompletion", taskId);
            activeSessions.remove(finalSessionId);
        });

        return emitter;
    }

    /**
     * <h3>停止正在运行的 Agent 会话</h3>
     * <p>
     * 主动停止指定会话的 Agent 执行。取消 ReactAgent 流式订阅（后台不再继续跑）。
     * 任务状态：订阅取消后由 agent_task 心跳补偿扫描（10 分钟）兜底标记 FAILED，
     * 可重提——比旧实现"停止后标记 SUCCESS"更诚实（用户中止 ≠ 成功）。
     * </p>
     *
     * @param sessionId 要停止的会话 ID
     * @return {@code "stopped"} 表示成功停止；
     *         {@code "no_active_session"} 表示该会话 ID 不存在或已结束
     */
    public String stopAgent(String sessionId) {
        SseEmitter emitter = activeSessions.remove(sessionId);
        if (emitter != null) {
            agentLoopExecutor.stop(sessionId);
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            return "stopped";
        }
        return "no_active_session";
    }
}
