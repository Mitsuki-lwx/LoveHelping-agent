// 包声明，定义该类所在的包路径
package cn.lwx.lwxaiagent.advisor;

// 导入 ChatClientRequest 类，用于处理聊天客户端请求
import org.springframework.ai.chat.client.ChatClientRequest;

// 导入 ChatClientResponse 类，用于处理聊天客户端响应
import org.springframework.ai.chat.client.ChatClientResponse;

// 导入 AdvisorChain 接口，用于管理顾问链的执行顺序
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;

// 导入 BaseAdvisor 接口，作为顾问的基础接口
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

// 导入 PromptTemplate 类，用于构建和渲染提示模板
import org.springframework.ai.chat.prompt.PromptTemplate;

// 导入 Map 类，用于存储键值对数据
import java.util.HashMap;
import java.util.Map;

// 通过“重读”提示强化用户问题，提升模型理解稳定性。
public class ReReadingAdvisor implements BaseAdvisor {

    // 默认模板：把用户问题重复一次，属于提示工程手段。
    // 边界：不会改写问题，只做强调，避免引入新语义。
    private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
			{re2_input_query}
			Read the question again: {re2_input_query}
			""";

    // 保存模板文本，便于按需替换。
    private final String re2AdviseTemplate;

    // 执行顺序：数值越小越先执行。
    private int order = 0;

    // 无参构造函数，使用默认的提示模板初始化
    public ReReadingAdvisor() {
        this(DEFAULT_RE2_ADVISE_TEMPLATE);
    }

    // 带参构造函数，允许自定义提示模板
    public ReReadingAdvisor(String re2AdviseTemplate) {
        this.re2AdviseTemplate = re2AdviseTemplate;
    }

    // 实现 before 方法，在请求发送前对请求进行处理
    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // 构建增强后的用户消息：把原始问题填进模板。
        // 注意：这里不做“纠错/润色”，只做重复提示。
        String augmentedUserText = PromptTemplate.builder()
                .template(this.re2AdviseTemplate)
                .variables(Map.of("re2_input_query", chatClientRequest.prompt().getUserMessage().getText()))// 将原始查询插入到模板中
                .build()// 构建提示模板
                .render();// 渲染提示模板

        // mutate() 会复制原请求，避免直接修改原对象。
        return chatClientRequest.mutate()//mutate（）是一个方法，用于创建一个新的对象，该对象与原始对象具有相同的属性值，但可以进行修改。
                // augmentUserMessage 会把增强文本附加到用户消息中。
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))// 将增强后的消息设置为用户消息
                //.context("userMessage", "XXXX")
                .build();
    }

    // 实现 after 方法，在接收到响应后进行处理（当前直接返回原响应）
    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // 本顾问不改写响应，直接透传。
        return chatClientResponse;
    }

    // 实现 getOrder 方法，返回顾问的执行顺序
    @Override
    public int getOrder() {
        // 返回顾问顺序，便于与其它 Advisor 协作。
        return this.order;
    }

    // 提供设置执行顺序的方法，并返回当前实例以支持链式调用
    public ReReadingAdvisor withOrder(int order) {
        this.order = order;
        return this;
    }

}