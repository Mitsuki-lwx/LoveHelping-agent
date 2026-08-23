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

import java.util.List;

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
                        SkillRetriever skillRetriever) {
        this.memoryStore = memoryStore;
        this.skillRetriever = skillRetriever;

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
        return execute(message, chatId, caps, null);
    }

    /**
     * 执行 with 可选 system prompt 覆盖（用于沙盘等需要注入人格参数的场景）。
     * @param customSystemPrompt 非 null 时覆盖默认 SYSTEM_PROMPT
     */
    public AgentResult.ShallowResult execute(String message, String chatId, CapabilitySet caps, String customSystemPrompt) {
        String tid = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "default";
        String context = assembleContext(message, tid);
        String effectivePrompt = customSystemPrompt != null ? customSystemPrompt : SYSTEM_PROMPT;
        var req = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId));
        req.system(effectivePrompt + context);
        return new AgentResult.ShallowResult(req.stream().content());
    }

    private String assembleContext(String message, String tid) {
        String memoryContext = memoryStore.retrieveAsContext(TenantContext.getUserId(), message);
        String skillContext = skillRetriever.retrieveAsContext(message, tid);
        if (memoryContext.isEmpty()) return skillContext;
        return memoryContext + skillContext;
    }
}