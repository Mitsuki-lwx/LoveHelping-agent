package cn.lwx.lwxaiagent.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.lwx.lwxaiagent.agent.model.AgentState;
import cn.lwx.lwxaiagent.tools.TerminateTool;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @description: ToolCallAgent是一个工具调用代理类，负责管理和调用各种工具，以支持Agent的功能实现。
 * 该类可以包含工具注册、工具调用、工具结果处理等功能，以便Agent能够灵活地使用不同的工具来完成任务。
 *
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)//继承父类的equals和hashCode方法
public class ToolCallAgent extends ReActAgent{
    //工具调用管理器
    private final ToolCallingManager toolCallingManager;
    //可用的工具列表
    private final ToolCallback[] avilableTools;
    //工具调用的ChatResponse对象
    private ChatResponse toolCallChatResponse;
    //聊天选项，用于设置内部工具执行功能为禁用
    private final ChatOptions ChatOptions;
    //标记nextStepPrompt是否已添加，避免重复追加
    private boolean nextStepPromptAdded = false;

    public ToolCallAgent(ToolCallback[] avilableTools) {
        //继承父类的构造方法
        super();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.avilableTools = avilableTools;
        //创建ChatOptions对象，并设置内部工具执行功能为禁用，这样在聊天过程中不会自动执行工具调用，而是由Agent根据需要手动触发工具调用。
        this.ChatOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

    }

    @Override
    public boolean think() {//思考方法，返回是否需要行动
        try {
            //校验提示词，只在第一步添加nextStepPrompt，避免重复
            if(!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())){
                UserMessage userMessage = new UserMessage(getNextStepPrompt());
                getMessageList().add(userMessage);
                nextStepPromptAdded = true;
            }

            //调用大模型。获取工具调用结果
            //创建List<Message>对象，得到上下文
            List<Message> messageList = getMessageList();
            //创建Prompt对象，包含上下文和聊天选项
            Prompt prompt = new Prompt(messageList, ChatOptions);
            //调用大模型
            ChatResponse chatResponse= getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(avilableTools)
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            //解析工具调用结果。获取要调用的工具名称和参数
            //助手信息
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            //工具调用结果
            String result  = assistantMessage.getText();
            log.info("result: {}", result);
            //工具调用列表
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            log.info("toolCallList: {}", toolCallList);
            //工具调用信息
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("Tool call: %s, Parameters: %s", toolCall.name(), toolCall.arguments()))
                    .collect(Collectors.joining("\n"));
            log.info("toolCallInfo: {}", toolCallInfo);
            //如果不需要工具调用，则返回false
            if (toolCallList.isEmpty()) {
                //只有不调用工具时，才需要手动添加助手信息
                getMessageList().add(assistantMessage);
                return false;
            }else {
                return true;
            }
            //异常处理
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            //添加助手信息
            getMessageList().add(new AssistantMessage(e.getMessage()));
            return false;
        }
    }

    @Override
    public String act() {//行动方法，执行工具调用并处理结果
        if(!toolCallChatResponse.hasToolCalls()){
            return "no tools need use";
        }
        //创建Prompt对象，包含上下文和聊天选项
        Prompt prompt = new Prompt(getMessageList(), this.ChatOptions);
        // 执行工具调用（调用大模型返回的要调用的工具，比如文件操作、PDF生成等）
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 把工具调用的结果（Assistant消息 + 工具响应消息）更新到上下文中
        setMessageList(toolExecutionResult.conversationHistory());
        //  取出最后一条消息，强制转成 ToolResponseMessage
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 121-122 - 检查工具响应列表中是否有 "doTerminate" 这个工具被调用了
        boolean isTerminate = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (isTerminate) {
            //停止
            setState(AgentState.FINISHED);
        }
        String result = toolResponseMessage.getResponses().stream()
                .map(response->"tools" + response.name() + "result: "+ response.responseData())
                .collect(Collectors.joining("\n"));

        log.info("result: {}", result);
        return result;
    }
}
