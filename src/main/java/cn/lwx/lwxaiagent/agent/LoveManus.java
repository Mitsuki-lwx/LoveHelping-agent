package cn.lwx.lwxaiagent.agent;

import cn.lwx.lwxaiagent.harness.MyLoggerAdvisor;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class LoveManus extends ToolCallAgent{

    public LoveManus(ToolCallback[] avilableTools, @Qualifier("deepSeekChatModel") ChatModel deepseekChatModel) {
        super(avilableTools);
        this.setName("LoveManus");

        // ✅ System Prompt：恋爱帮帮帮 核心身份与哲学
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