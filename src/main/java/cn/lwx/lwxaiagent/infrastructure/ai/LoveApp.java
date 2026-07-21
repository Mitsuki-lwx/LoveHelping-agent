package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import cn.lwx.lwxaiagent.harness.governance.GuardrailAdvisor;
import cn.lwx.lwxaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
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

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份,告知用户可倾诉恋爱难题。"+
    "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰;"+
            "恋爱状态询问沟通、习惯差异引发的矛盾;已婚状态询问家庭责任与亲属关系处理的问题。"+
            "引导用户详述事情经过、对方反应及自身想法,以便给出专属解决方案。"+
            "适当在回复内容中穿插一些小图案或emoji（如💕🌸✨💝🌹等）来增强氛围感，让回复更生动温暖。"+
            ""+
            "【反问原则】如果用户的问题描述模糊、缺少关键信息（如只说「她生气了怎么办」「怎么追女生」「吵架了」等），"+
            "不要直接给建议。必须先反问 2-3 个问题把情况弄清楚，信息足够后再给出针对性建议。"+
            "反问的内容应围绕：事情经过、双方关系阶段、用户已采取的行动、对方的反应。";
    @Resource
    private Advisor loveAppRagCloudAdvisor;

    @Resource
    private VectorStore PgVectorVectorStore;

    public LoveApp(JdbcChatMemoryRepository chatMemoryRepository, ChatModel chatModel,
                   GuardrailAdvisor guardrailAdvisor) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
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

    public Flux<String> doChatByStream(String message, String chatId) {
        Flux<String> content1 =chatClient
                .prompt()
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .stream()
                .content();

        return content1;
    }

    public String doChat(String message, String chatId) {
        ChatResponse chatResponse =chatClient
                .prompt()
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content:{}",content);
        return content;
    }

    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport =chatClient
                .prompt()
                .system(SYSTEM_PROMPT+"每次对话后都要生成详细理性的恋爱结果，标题为{用户名}的恋爱报告，内容为公正客观的建议列表")
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport:{}",loveReport);
        return loveReport;
    }

    @Resource
    private QueryRewriter queryRewriter;
    public String doChatWithRAG(String message, String chatId) {
        QuestionAnswerAdvisor questionAnswerAdvisorWithPgVectorStore = QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();
        ChatResponse chatResponse =chatClient
                .prompt()
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .advisors(questionAnswerAdvisorWithPgVectorStore)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    public Flux<String> doChatByStreamWithRAG(String message, String chatId) {
        QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .advisors(ragAdvisor)
                .stream()
                .content();
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    public Flux<String> doChatByStreamWithTools(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(allTools)
                .stream()
                .content();
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
