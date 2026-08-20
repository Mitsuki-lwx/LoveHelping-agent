package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.evolution.SkillRetriever;
import cn.lwx.lwxaiagent.harness.governance.GuardrailAdvisor;
import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.memory.MemoryStore;
import cn.lwx.lwxaiagent.rag.ParentChildDocumentRetriever;
import cn.lwx.lwxaiagent.rag.QueryRewriter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <h1>浅层聊天执行器 —— 一次 LLM 调用 + 按需能力叠加</h1>
 *
 * <p>封装 ChatClient 的核心调用逻辑（组装上下文、追加 advisor/tools、流式输出），
 * 取代原 ChatService 中 5 个手动拼装的聊天方法。</p>
 *
 * <p>记忆（MessageChatMemory）始终开启（通过 ChatClient defaultAdvisors）；
 * 工具和 RAG 由 {@link CapabilitySet} 按需追加。</p>
 */
@Slf4j
@Component
public class ChatExecutor {

    /** 统一 ChatClient（defaultAdvisors: memory + logger + guardrail） */
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    /** 全量工具集（ToolRegistration 注入） */
    private final ToolCallback[] allTools;

    /** RAG 相关依赖（构建 Retriever + QueryTransformer） */
    private final ParentChildDocumentRetriever parentChildDocumentRetriever;
    private final QueryRewriter queryRewriter;
    @Value("${app.rag.query-rewrite.enabled:true}")
    private boolean queryRewriteEnabled;

    /** 记忆存储（用户档案/事实跨会话注入） */
    private final MemoryStore memoryStore;

    /** 技能检索器（SkillRetriever，进化模块产物） */
    private final SkillRetriever skillRetriever;

    /** 统一系统提示词（消除 ChatService/AgentLoopExecutor 双份分裂） */
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

            IMPORTANT: Always respond in the same language as the user's message.
            """;

    public ChatExecutor(org.springframework.ai.chat.model.ChatModel chatModel,
                        ToolCallback[] allTools,
                        ChatMemoryFactory chatMemoryFactory,
                        GuardrailAdvisor guardrailAdvisor,
                        ParentChildDocumentRetriever parentChildDocumentRetriever,
                        QueryRewriter queryRewriter,
                        MemoryStore memoryStore,
                        SkillRetriever skillRetriever) {
        this.allTools = allTools;
        this.parentChildDocumentRetriever = parentChildDocumentRetriever;
        this.queryRewriter = queryRewriter;
        this.memoryStore = memoryStore;
        this.skillRetriever = skillRetriever;

        // 构建统一 ChatClient（defaultAdvisors: memory + logger + guardrail）
        ChatMemory chatMemory = chatMemoryFactory.create();
        this.chatClient = org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor(),
                        guardrailAdvisor)
                .build();
    }

    /**
     * 浅层执行：一次 LLM 调用 + 按需能力叠加。
     */
    public AgentResult.ShallowResult execute(String message, String chatId, CapabilitySet caps) {
        String tid = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : "default";

        // 组装上下文（记忆 + 技能，始终执行）
        String context = assembleContext(message, tid);

        // 构建请求
        var req = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, chatId));

        // 按需叠加 RAG
        if (caps.rag()) {
            req.advisors(buildRagAdvisor());
        }

        // 按需叠加工具
        if (!caps.tools().isEmpty()) {
            req.toolCallbacks(caps.tools());
        }

        // 注入系统提示词 + 上下文
        req.system(SYSTEM_PROMPT + context);

        return new AgentResult.ShallowResult(req.stream().content());
    }

    /**
     * 构建 RAG Advisor（按需，每次调用创建新实例——无状态，开销极低）。
     */
    private RetrievalAugmentationAdvisor buildRagAdvisor() {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(parentChildDocumentRetriever)
                .queryTransformers(queryRewriteEnabled
                        ? new org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer[]{queryRewriter}
                        : new org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer[0])
                .build();
    }

    /**
     * 组装上下文：用户记忆（跨会话）+ 技能检索。
     * 修复：原 syncChat 只取 skillContext（不一致），现在统一取两者。
     */
    String assembleContext(String message, String tid) {
        String memoryContext = memoryStore.retrieveAsContext(TenantContext.getUserId());
        String skillContext = skillRetriever.retrieveAsContext(message, tid);
        if (memoryContext.isEmpty()) {
            return skillContext;
        }
        return memoryContext + skillContext;
    }

    /** 返回全量工具集（供 ChatEntry 构建 CapabilitySet） */
    public List<ToolCallback> getAllTools() {
        return List.of(allTools);
    }
}
