package cn.lwx.lwxaiagent.app;

import cn.lwx.lwxaiagent.advisor.MyLoggerAdvisor;
import cn.lwx.lwxaiagent.advisor.ReReadingAdvisor;
import cn.lwx.lwxaiagent.rag.LoveAppRagCustomAdvisorFactory;
import cn.lwx.lwxaiagent.rag.MyKeywordEnricher;
import cn.lwx.lwxaiagent.rag.QueryRewriter;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
// 核心业务入口：封装恋爱咨询的对话能力。
public class LoveApp {
    private final ChatClient chatClient;

    // 系统提示词：定义角色与提问边界，避免偏离恋爱咨询主题。
    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份,告知用户可倾诉恋爱难题。"+
    "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰;"+
            "恋爱状态询问沟通、习惯差异引发的矛盾;已婚状态询问家庭责任与亲属关系处理的问题。"+
            "引导用户详述事情经过、对方反应及自身想法,以便给出专属解决方案。"+
            "适当在回复内容中穿插一些小图案或emoji（如💕🌸✨💝🌹等）来增强氛围感，让回复更生动温暖。";
    @Resource
    private Advisor loveAppRagCloudAdvisor;


    @Resource//按照类型注入
    private VectorStore PgVectorVectorStore;



    /**
     * 初始化ChatClint
     * @param deepseekChatModel
     */
    public LoveApp(ChatModel deepseekChatModel, JdbcChatMemoryRepository chatMemoryRepository) {
        // 依赖注入：ChatModel 由 Spring 提供，避免手动创建。

        // 初始化基于 JDBC 的对话记忆：跨进程持久化、适合多轮对话。
        ChatMemory chatMemoryByJDBC = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
        // 初始化基于内存的对话记忆：仅当前进程可用，用于快速验证。
        ChatMemory chatMemory = MessageWindowChatMemory.builder()// 创建一个基于内存的 ChatMemory 对象，MessageWindowChatMemory
                .chatMemoryRepository(new InMemoryChatMemoryRepository())// 设置 ChatMemoryRepository，这是一个存储对话记忆的仓库，用于存储对话中的消息
                .maxMessages(10) // 这个是最大返回的消息数量
                .build();
        chatClient = ChatClient.builder(deepseekChatModel)
                // 默认系统提示：所有对话都会自动带上。
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        // 绑定对话记忆，按 conversationId 关联上下文。
                        MessageChatMemoryAdvisor.builder(chatMemoryByJDBC)
                                .build(),
                        // 日志
                        new MyLoggerAdvisor()
                        // 重新阅读,但是会消耗两倍的token
                        //new ReReadingAdvisor()


                )
                .build();

    }

    // 结构化输出模型：标题与建议列表。
    record LoveReport(String title, List<String> suggestions) {}

    /**
     * AI 基础对话（支持多轮对话记忆,SSE）
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        // chatId 是对话分组标识；同一 chatId 才会共享记忆。
        Flux<String> content1 =chatClient
                .prompt()//这一步是创建一个 Prompt 对象，并设置用户输入的消息
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))//这里的意思是设置对话的ID，这个ID是唯一的
                .stream()
                .content();//这里是流式输出，

        return content1;
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        // chatId 是对话分组标识；同一 chatId 才会共享记忆。
        ChatResponse chatResponse =chatClient
                .prompt()//这一步是创建一个 Prompt 对象，并设置用户输入的消息
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))//这里的意思是设置对话的ID，这个ID是唯一的
                .call()
                .chatResponse();//这里是获取 ChatResponse 对象，里面包括输入、输出、错误等信息
        String content = chatResponse.getResult().getOutput().getText();//这里的链式调用分别是：从response中获取结果，再获取输出，最后获取输出的文本
        log.info("content:{}",content);
        return content;
    }

    /**
     * AI 恋爱报告
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        // 要求模型输出结构化内容，便于前端展示与二次处理。
        LoveReport loveReport =chatClient
                .prompt()//这一步是创建一个 Prompt 对象，并设置用户输入的消息
                .system(SYSTEM_PROMPT+"每次对话后都要生成详细理性的恋爱结果，标题为{用户名}的恋爱报告，内容为公正客观的建议列表")
                // system() 会覆盖默认系统提示，因此这里拼接原提示。
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport:{}",loveReport);
        return loveReport;
    }


    //RAG 知识库对话
//    @Resource
//    private VectorStore LoveAppVectorStore;

    @Resource
    private QueryRewriter queryRewriter;
    public String doChatWithRAG(String message, String chatId) {
        // RAG 预处理：基于 LLM 改写查询
        //String rewrittenQuery = queryRewriter.doRewrite(message);
        // 添加RAG拦截器，基于向量搜索
        //QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(LoveAppVectorStore).build();//创建一个基于向量搜索的Advisor
        QuestionAnswerAdvisor questionAnswerAdvisorWithPgVectorStore = QuestionAnswerAdvisor.builder(PgVectorVectorStore).build();//创建一个基于本地向量数据库搜索的Advisor
        ChatResponse chatResponse =chatClient
                .prompt()//这一步是创建一个 Prompt 对象，并设置用户输入的消息
                .user( message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))//这里的意思是设置对话的ID，这个ID是唯一的
                //RAG知识库问答
                //.advisors(questionAnswerAdvisor)
                //RAG检索增强（基于百炼）
                //.advisors(loveAppRagCloudAdvisor)
                //RAG检索增强（基于本地向量数据库）
                .advisors(questionAnswerAdvisorWithPgVectorStore)
                //.advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(LoveAppVectorStore, "单身"))
                .call()
                .chatResponse();//这里是获取 ChatResponse 对象，里面包括输入、输出、错误等信息
        String content = chatResponse.getResult().getOutput().getText();//这里的链式调用分别是：从response中获取结果，再获取输出，最后获取输出的文本
//        log.info("content:{}",content);
        return content;
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(new MyLoggerAdvisor())
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .toolCallbacks(allTools)  // ✅ 直接传数组，不用转换
                .call()
                .chatResponse();
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
