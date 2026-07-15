package cn.lwx.lwxaiagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;

// 示例：在应用启动时调用一次模型，便于本地验证。
public class SpringAiAiInvoke implements CommandLineRunner {

    @Resource
    private ChatModel dashscopeChatModel;

    @Override
    public void run(String... args) throws Exception {
        // CommandLineRunner 会在 Spring 启动完成后执行。
        // 单次同步调用，适合作为最小可用性检查。
        AssistantMessage message = dashscopeChatModel.call(new Prompt("你好"))
                .getResult()
                .getOutput();
        // 输出模型文本到控制台，避免引入其他依赖。
        System.out.println(message.getText());
    }
}
