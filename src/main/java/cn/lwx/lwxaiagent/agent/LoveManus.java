package cn.lwx.lwxaiagent.agent;

import cn.lwx.lwxaiagent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

@Component
public class LoveManus extends ToolCallAgent{

    public LoveManus(ToolCallback[] avilableTools, ChatModel deepseekChatModel) {
        super(avilableTools);
        this.setName("LoveManus");

        // ✅ System Prompt：定义"你是谁"
        String SYSTEM_PROMPT = """
            You are LoveManus, a general-purpose AI assistant with tools at your disposal.
            Think briefly, act decisively, and respond in the user's language.
            Never include debug text, raw tool output, or escaped characters.
            """;
        this.setSystemPrompt(SYSTEM_PROMPT);

        String NextStepPrompt = """
            You have tools available — use them as needed to complete the task.
            Break complex requests into steps. When done, call the terminate tool.
            Do not list raw tool output or URLs in your thinking.
            """;
        this.setNextStepPrompt(NextStepPrompt);

        ChatClient chatClient = ChatClient.builder(deepseekChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }
}