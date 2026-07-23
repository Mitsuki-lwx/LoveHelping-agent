package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import cn.lwx.lwxaiagent.harness.governance.GuardrailAdvisor;
import cn.lwx.lwxaiagent.memory.ChatMemoryFactory;
import cn.lwx.lwxaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
public class LoveApp {
    private final ChatClient chatClient;

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
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private VectorStore PgVectorVectorStore;

    public LoveApp(ChatMemoryFactory chatMemoryFactory, ChatModel chatModel,
                   GuardrailAdvisor guardrailAdvisor) {
        ChatMemory chatMemory = chatMemoryFactory.create();
        chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor(),
                        guardrailAdvisor
                )
                .build();

    }

    record LoveReport(String title, List<String> suggestions) {}

    // ==================== Stream methods with skill context ====================

    public Flux<String> doChatByStream(String message, String chatId, String skillContext) {
        var prompt = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId));

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        return prompt.stream().content();
    }

    public String doChat(String message, String chatId, String skillContext) {
        var prompt = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId));

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        ChatResponse chatResponse = prompt.call().chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content:{}", content);
        return content;
    }

    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "\nAfter each conversation, provide a detailed and objective relationship analysis report titled after the user's name, with fair and actionable suggestions.")
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport:{}", loveReport);
        return loveReport;
    }

    public Flux<String> doChatByStreamWithRAG(String message, String chatId, String skillContext) {
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();

        var prompt = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .advisors(ragAdvisor);

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        return prompt.stream().content();
    }

    public String doChatWithRAG(String message, String chatId, String skillContext) {
        QuestionAnswerAdvisor questionAnswerAdvisorWithPgVectorStore =
                QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();

        var prompt = chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .advisors(questionAnswerAdvisorWithPgVectorStore);

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        ChatResponse chatResponse = prompt.call().chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    public Flux<String> doChatByStreamWithTools(String message, String chatId, String skillContext) {
        var prompt = chatClient.prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(allTools);

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        return prompt.stream().content();
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId, String skillContext) {
        var prompt = chatClient.prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(allTools);

        if (skillContext != null && !skillContext.isEmpty()) {
            prompt.system(SYSTEM_PROMPT + skillContext);
        }

        ChatResponse response = prompt.call().chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    @Resource
    private ToolCallbackProvider toolCallbackProvider;

    public String doChatWithMCP(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }
}
