package cn.lwx.lwxaiagent.chatMemory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public class FileChatMemory implements ChatMemory {

    // 这是占位实现：未接入文件持久化逻辑。
    // 适用场景：练习接口实现或做原型验证。

    @Override
    public void add(String conversationId, Message message) {
        // 默认实现会直接委派给接口的默认方法。
        ChatMemory.super.add(conversationId, message);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // TODO: 需要定义文件格式与追加写入策略。
        // 建议：按 conversationId 分文件或按时间滚动。
    }

    @Override
    public List<Message> get(String conversationId) {
        // 暂不提供读取能力，避免返回误导性数据。
        // TODO: 需要定义反序列化与过滤逻辑。
        return List.of();
    }

    @Override
    public void clear(String conversationId) {
        // TODO: 需要定义文件删除或标记清理策略。
        // 注意：清理要与读取策略保持一致。
    }
}
