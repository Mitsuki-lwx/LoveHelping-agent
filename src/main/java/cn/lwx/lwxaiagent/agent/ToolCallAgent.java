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
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)
public class ToolCallAgent extends ReActAgent {

    private final ToolCallingManager toolCallingManager;
    private final ToolCallback[] avilableTools;
    private ChatResponse toolCallChatResponse;
    private final ChatOptions ChatOptions;
    private boolean nextStepPromptAdded = false;

    public ToolCallAgent(ToolCallback[] avilableTools) {
        super();
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.avilableTools = avilableTools;
        this.ChatOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
    }

    @Override
    public boolean think() {
        try {
            String extraSystem = "";
            if (!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())) {
                extraSystem = "\n" + getNextStepPrompt();
                nextStepPromptAdded = true;
            }

            List<Message> messageList = getMessageList();
            Prompt prompt = new Prompt(messageList, ChatOptions);
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt() + extraSystem)
                    .toolCallbacks(avilableTools)
                    .call()
                    .chatResponse();
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();

            log.info("result: {}, toolCalls: {}", assistantMessage.getText(), toolCallList);

            if (toolCallList == null || toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            getMessageList().add(new AssistantMessage(e.getMessage()));
            return false;
        }
    }

    public boolean streamThink(SseEmitter emitter) {
        try {
            String extraSystem = "";
            if (!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())) {
                extraSystem = "\n" + getNextStepPrompt();
                nextStepPromptAdded = true;
            }

            List<Message> messageList = getMessageList();
            Prompt prompt = new Prompt(messageList, ChatOptions);

            List<ChatResponse> responses = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();

            getChatClient().prompt(prompt)
                    .system(getSystemPrompt() + extraSystem)
                    .toolCallbacks(avilableTools)
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        responses.add(response);
                        AssistantMessage msg = response.getResult().getOutput();
                        if (msg != null) {
                            // For DashScope/DeepSeek: tool call deltas have null text,
                            // only final answer deltas have text. Guard in case other
                            // providers mix tool call metadata into text field.
                            boolean hasToolCalls = msg.getToolCalls() != null
                                    && !msg.getToolCalls().isEmpty();
                            String text = msg.getText();
                            if (text != null && !text.isEmpty() && !hasToolCalls) {
                                fullText.append(text);
                                try {
                                    emitter.send(text);
                                } catch (IOException e) {
                                    log.warn("SSE send failed: {}", e.getMessage());
                                }
                            }
                        }
                    })
                    .blockLast();

            // 用流式最后一条 response（含聚合完成的 tool calls）作为 act 依据
            ChatResponse lastResp = null;
            for (int ri = responses.size() - 1; ri >= 0; ri--) {
                AssistantMessage m = responses.get(ri).getResult().getOutput();
                if (m != null && m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                    lastResp = responses.get(ri);
                    break;
                }
            }
            if (lastResp == null && !responses.isEmpty()) {
                lastResp = responses.get(responses.size() - 1);
            }
            this.toolCallChatResponse = lastResp;

            List<AssistantMessage.ToolCall> toolCalls = lastResp != null
                    && lastResp.getResult().getOutput() != null
                    ? lastResp.getResult().getOutput().getToolCalls() : null;
            if (toolCalls == null || toolCalls.isEmpty()) {
                getMessageList().add(new AssistantMessage(fullText.toString()));
                return false;
            }
            return true;

        } catch (Exception e) {
            log.error("streamThink error: {}", e.getMessage());
            try {
                emitter.send("Agent encountered an error: " + e.getMessage());
            } catch (IOException ex) {
                log.warn("Failed to send streamThink error to SSE: {}", ex.getMessage());
            }
            getMessageList().add(new AssistantMessage(e.getMessage()));
            return false;
        }
    }

    @Override
    public void resetForNextTurn() {
        super.resetForNextTurn();
        this.nextStepPromptAdded = false;
    }

    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "no tools need use";
        }
        Prompt prompt = new Prompt(getMessageList(), this.ChatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        setMessageList(toolExecutionResult.conversationHistory());
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        boolean isTerminate = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));
        if (isTerminate) {
            setState(AgentState.FINISHED);
        }
        String result = toolResponseMessage.getResponses().stream()
                .map(response -> "tools" + response.name() + "result: " + response.responseData())
                .collect(Collectors.joining("\n"));
        log.info("act result: {}", result);
        return result;
    }
}
