package cn.lwx.lwxaiagent.agent;

import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * <h1>恋爱帮帮帮（LoveManus）——项目的核心 Agent 实现</h1>
 *
 * <p>继承自 {@link ToolCallAgent}，是整个 AI 恋爱助手应用的<b>唯一具体 Agent 实例</b>。
 * 它被注册为 Spring Bean（{@code @Component}），由容器管理生命周期。</p>
 *
 * <p><b>LoveManus 的职责：</b></p>
 * <ul>
 *   <li>配置专属的系统提示词（SYSTEM_PROMPT），定义"恋爱帮帮帮"的身份和风格</li>
 *   <li>配置下一步操作提示（nextStepPrompt），指导 LLM 如何使用工具</li>
 *   <li>配置 ChatClient（包括 Advisor 拦截器链），处理 LLM 调用的前置/后置逻辑</li>
 *   <li>绑定 DeepSeek ChatModel 作为底层大模型引擎</li>
 * </ul>
 *
 * <p><b>系统提示词设计要点：</b></p>
 * <ul>
 *   <li>身份定位：温暖、专业的恋爱关系 AI 助手</li>
 *   <li>核心哲学："知行合一"——深入思考后果断行动</li>
 *   <li>语言自适应：根据用户输入语言自动切换回复语言（中文/英文）</li>
 *   <li>禁止输出调试信息、原始工具输出、转义字符等对用户无意义的内容</li>
 * </ul>
 *
 * <p><b>继承链回顾：</b></p>
 * <pre>
 *   BaseAgent → ReActAgent → ToolCallAgent → LoveManus（本类）
 *     基础属性    think/act    FunctionCall    系统提示词+模型配置
 * </pre>
 *
 * @see ToolCallAgent 父类——提供了 think/act 的具体实现
 * @see BaseAgent#runStream 入口方法——启动 Agent 执行循环
 */
@Component  // 注册为 Spring Bean，可被 AiController 注入使用
public class LoveManus extends ToolCallAgent {

    /**
     * 构造 LoveManus 实例
     *
     * <p><b>构造过程（DI 注入 + 手动配置）：</b></p>
     * <ol>
     *   <li>{@code ToolCallback[]}：Spring 容器自动注入所有 {@code @Tool} 标注的方法</li>
     *   <li>{@code ChatModel}：通过 {@code @Qualifier("deepSeekChatModel")} 明确指定使用 DeepSeek 模型</li>
     *   <li>设置 Agent 名称为 "LoveManus"（在日志和调试中显示）</li>
     *   <li>配置 SYSTEM_PROMPT（核心身份和价值观定义）</li>
     *   <li>配置 nextStepPrompt（指导 LLM 使用工具的提示）</li>
     *   <li>构建 ChatClient（绑定模型 + 配置 Advisor 拦截器链）</li>
     * </ol>
     *
     * @param avilableTools     所有可用的工具回调（Spring 自动注入 @Tool 方法）
     * @param deepseekChatModel DeepSeek 聊天模型实例（通过 @Qualifier 指定）
     */
    public LoveManus(ToolCallback[] avilableTools, @Qualifier("deepSeekChatModel") ChatModel deepseekChatModel) {
        // 调用父类 ToolCallAgent 的构造器，传入工具列表
        super(avilableTools);
        this.setName("LoveManus");  // 设置 Agent 名称

        // ==================== 系统提示词：定义 Agent 的核心身份 ====================
        String SYSTEM_PROMPT = """
            You are 恋爱帮帮帮 (LoveHelper), a warm and professional AI assistant specializing in love and relationships.
            知行合一 — deep thinking meets decisive action.

            Core identity:
            - Name: 恋爱帮帮帮
            - Philosophy: 知行合一 — think thoroughly, then act decisively
            - Style: Warm, empathetic, and effective. Never include debug text, raw tool output, or escaped characters.

            When asked "你是谁" or "who are you", always say you are 恋爱帮帮帮 and briefly explain your purpose.

            CRITICAL: Your internal reasoning and final response MUST be in the same language as the user's latest message.
            If the user writes in Chinese, reason and reply in Chinese.
            If the user writes in English, reason and reply in English.
            This is a strict requirement — your chain-of-thought and output must match the user's language.
            """;
        this.setSystemPrompt(SYSTEM_PROMPT);

        // ==================== 下一步操作提示：指导 LLM 如何使用工具 ====================
        String NextStepPrompt = """
            You have tools available — use them as needed to complete the task.
            Break complex requests into steps. When done, call the terminate tool.
            Do not list raw tool output or URLs in your thinking.
            """;
        this.setNextStepPrompt(NextStepPrompt);

        // ==================== 构建 ChatClient ====================
        // ChatClient 是 Spring AI 的 ChatModel 高层封装，提供流式调用、Advisor 拦截器等能力
        ChatClient chatClient = ChatClient.builder(deepseekChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())  // 添加日志 Advisor（记录每次 LLM 调用的输入输出）
                .build();
        this.setChatClient(chatClient);
    }
}