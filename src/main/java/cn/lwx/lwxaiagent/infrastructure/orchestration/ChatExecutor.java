package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.evolution.SkillRetriever;
import cn.lwx.lwxaiagent.harness.governance.GuardrailAdvisor;
import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.memory.MemoryStore;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

/**
 * 普通聊天执行器：ChatClient 一次 LLM 调用，无工具无 RAG。
 * 记忆通过 MessageChatMemoryAdvisor 自动注入（始终开启）。
 */
@Slf4j
@Component
public class ChatExecutor {

    private final ChatClient chatClient;
    private final MemoryStore memoryStore;
    private final SkillRetriever skillRetriever;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor> ragAdvisor;

    /** 话术三级（FR-CORE-01）SSE 结构化事件标记：流末尾 append，SSE 桥接识别后剥离 */
    public static final String ADVICE_EVENT_MARKER = "@@ADVICE@@";

    /** 话术请求激活段（ADR-18：命中后追加，强化三牌结构输出，伦理红线不放松） */
    private static final String ADVICE_ACTIVATE_PROMPT = """

            【话术三级·激活】用户已明确请求沟通建议（回复方案/如何开口/怎么道歉）。此时**优先于上方的"先澄清问题"原则**：
            直接按下列严格格式输出三套方案；只有当关键事实完全缺失时，才允许先简短问一个问题，但问题之后仍必须附上基于现有信息的三套方案——不得只提问不发方案。
            每套都必须包含"具体可说的话"与"对方可能反应"，不要省略任何一块：
            🛡️ 安全牌（保守）: <具体可说的话>
              对方可能反应：<对方可能的回应>
            ⚡ 进击牌（主动）: <具体可说的话>
              对方可能反应：<对方的可能回应>
            🌸 后撤牌（给空间）: <具体可说的话>
              对方可能反应：<对方的可能回应>
            禁止输出操控、拿捏、打压、PUA 性质的话术；三牌只是给用户的说话选择。""";

    public static final String SYSTEM_PROMPT = """
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
            EXCEPTION: If the user explicitly asks for a concrete deliverable — writing a letter,
            drafting a reply/message, listing steps, giving a script/template, or answering a direct
            yes/no question — PRODUCE IT DIRECTLY based on what they gave, without asking questions
            first. After delivering, you may add ONE short optional follow-up ("如需更贴合实际，
            可以补充一点具体细节") — never ask questions instead of delivering.

            【Three-Tier Advice】When the user asks for communication advice (how to reply, what to say,
            how to respond to a situation), ALWAYS provide THREE tiers of advice:
            1. 🛡️ 安全牌（Safe）: Conservative, low-risk response that won't make things worse
            2. ⚡ 进击牌（Bold）: More proactive response that shows initiative
            3. 🌸 后撤牌（Retreat）: Graceful step-back that gives space while maintaining dignity
            For each tier, briefly explain why it works and what the other person might say back.
            IMPORTANT: These are choices for the user to consider, NOT manipulation tactics.
            The goal is helping the user communicate authentically, not control the other person.

            IMPORTANT: Always respond in the same language as the user's message.
            """;

    public ChatExecutor(org.springframework.ai.chat.model.ChatModel chatModel,
                        ChatMemoryFactory chatMemoryFactory,
                        GuardrailAdvisor guardrailAdvisor,
                        MemoryStore memoryStore,
                        SkillRetriever skillRetriever,
                        io.micrometer.core.instrument.MeterRegistry meterRegistry,
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                        org.springframework.beans.factory.ObjectProvider<org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor> ragAdvisor) {
        this.memoryStore = memoryStore;
        this.skillRetriever = skillRetriever;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.ragAdvisor = ragAdvisor;

        ChatMemory chatMemory = chatMemoryFactory.create();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor(),
                        guardrailAdvisor)
                .build();
    }

    /** 普通执行：一次 LLM 调用，无工具无 RAG */
    public AgentResult.ShallowResult execute(String message, String chatId, CapabilitySet caps) {
        return execute(message, chatId, caps, null, false);
    }

    /**
     * 执行 with 可选 system prompt 覆盖（用于沙盘等需要注入人格参数的场景）。
     * @param customSystemPrompt 非 null 时覆盖默认 SYSTEM_PROMPT
     */
    public AgentResult.ShallowResult execute(String message, String chatId, CapabilitySet caps, String customSystemPrompt) {
        return execute(message, chatId, caps, customSystemPrompt, false);
    }

    /**
     * 执行 with 话术三级开关（FR-CORE-01）。
     * @param customSystemPrompt 非 null 时覆盖默认 SYSTEM_PROMPT
     * @param advice             true=话术建议请求：追加激活段，流末尾附结构化 advice 事件（增量，向后兼容）
     */
    public AgentResult.ShallowResult execute(String message, String chatId, CapabilitySet caps,
                                             String customSystemPrompt, boolean advice) {
        return issuePrompt(message, chatId, customSystemPrompt, advice, false);
    }

    /**
     * 带完整 RAG 检索增强的执行（ADR-15，Task 7）：普通/沙盘节点用。
     * 在 prompt 上挂 {@link org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor}；
     * 简单问题节点用 {@link #execute}（不检索）。
     *
     * @param advisor 自定义 system prompt 覆盖（沙盘人格等），无则用默认
     */
    public AgentResult.ShallowResult executeWithRag(String message, String chatId,
                                                    String customSystemPrompt, boolean advice) {
        return issuePrompt(message, chatId, customSystemPrompt, advice, true);
    }

    /** 统一生成管线：advice 激活 + 可选 RAG advisor + advice 事件切片 */
    private AgentResult.ShallowResult issuePrompt(String message, String chatId,
                                                  String customSystemPrompt, boolean advice, boolean rag) {
        String tid = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "default";
        String context = assembleContext(message, tid);
        String effectivePrompt = customSystemPrompt != null ? customSystemPrompt : SYSTEM_PROMPT;
        if (advice) {
            effectivePrompt = effectivePrompt + ADVICE_ACTIVATE_PROMPT;
        }
        var req = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId));
        req.system(effectivePrompt + context);
        if (rag) {
            var advisor = ragAdvisor.getIfAvailable();
            if (advisor != null) {
                req.advisors(advisor); // 检索增强：改写→检索→上下文注入
            }
        }
        Flux<String> stream = req.stream().content();
        if (!advice) {
            return new AgentResult.ShallowResult(stream);
        }
        // 话术路径：文本实时流式输出；完整流结束后按三牌标记切片，追加结构化 advice 事件
        AtomicReference<StringBuilder> acc = new AtomicReference<>(new StringBuilder());
        Flux<String> enriched = stream
                .doOnNext(s -> acc.get().append(s))
                .concatWith(Flux.defer(() -> {
                    List<AdviceTier> tiers = sliceTiers(acc.get().toString());
                    if (tiers.size() < 2) {
                        return Flux.empty(); // 未切出三牌 → 降级纯文本（前端容错，ADR-18 代价项）
                    }
                    try {
                        meterRegistry.counter("advice.activated").increment();
                    } catch (Exception ignored) {}
                    try {
                        return Flux.just(ADVICE_EVENT_MARKER + toAdviceJson(tiers));
                    } catch (Exception e) {
                        log.warn("Advice payload serialization failed, fallback to plain text: {}", e.getMessage());
                        return Flux.empty();
                    }
                }));
        return new AgentResult.ShallowResult(enriched);
    }

    /**
     * 三牌切片（FR-CORE-01）：按 🛡️/⚡/🌸 分块，每块抽 content 与 reaction。
     * 公开静态便于单测；解析失败/不足两牌的块整体降级为纯文本。
     */
    public static List<AdviceTier> sliceTiers(String text) {
        if (text == null || text.isBlank()) return List.of();
        var matcher = java.util.regex.Pattern
                .compile("(🛡️|⚡|🌸)([^🛡️⚡🌸]*)").matcher(text);
        List<AdviceTier> tiers = new ArrayList<>();
        while (matcher.find()) {
            String marker = matcher.group(1);
            String body = matcher.group(2).trim();
            if (body.isEmpty()) continue;
            // 质量门槛：只有牌名、几字的"承诺句"（"我会给你🛡️安全牌、⚡进击牌"）不算有效牌
            if (body.length() < 8) continue;
            String name = switch (marker) {
                case "🛡️" -> "安全牌";
                case "⚡" -> "进击牌";
                default -> "后撤牌";
            };
            tiers.add(parseTier(name, body));
        }
        return tiers;
    }

    private static AdviceTier parseTier(String name, String body) {
        var rm = java.util.regex.Pattern
                .compile("(对方可能反应|可能反应|对方可能会|对方会|对方可能|对方大概|对方也许).*", java.util.regex.Pattern.DOTALL)
                .matcher(body);
        if (rm.find() && rm.start() > 0) {
            String content = body.substring(0, rm.start()).trim();
            String reaction = rm.group(0).trim();
            return new AdviceTier(name, content, reaction);
        }
        return new AdviceTier(name, body, "");
    }

    private String toAdviceJson(List<AdviceTier> tiers) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        for (AdviceTier t : tiers) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("content", t.content());
            m.put("reaction", t.reaction());
            list.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "advice");
        payload.put("tiers", list);
        return objectMapper.writeValueAsString(payload);
    }

    /** 话术三级单牌（FR-CORE-01）：名称 + 具体可说的话 + 对方可能反应 */
    public record AdviceTier(String name, String content, String reaction) {}

    private String assembleContext(String message, String tid) {
        String memoryContext = memoryStore.retrieveAsContext(TenantContext.getUserId(), message);
        String skillContext = skillRetriever.retrieveAsContext(message, tid);
        if (memoryContext.isEmpty()) return skillContext;
        return memoryContext + skillContext;
    }
}