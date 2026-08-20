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

/**
 * <h1>具备工具调用能力的 Agent 实现类</h1>
 *
 * <p>这是 ReAct 范式的<b>具体实现</b>，也是最核心的 Agent 类。它实现了 think（思考）和 act（执行）
 * 两个方法，通过 LLM 的 Function Calling 能力来调用外部工具完成任务。</p>
 *
 * <p><b>核心流程：</b></p>
 * <pre>
 *   用户输入
 *     ↓
 *   think() —— 将消息+可用工具列表发给 LLM
 *     ↓
 *   LLM 返回 toolCalls（要调用哪些工具）还是最终文本？
 *     ↓                              ↓
 *   有 toolCalls                   无 toolCalls
 *     ↓                              ↓
 *   act() —— 执行工具         直接返回文本给用户
 *     ↓
 *   将工具结果加入消息列表
 *     ↓
 *   回到 think()（循环直到完成或达到最大步数）
 * </pre>
 *
 * <p><b>关键依赖：</b></p>
 * <ul>
 *   <li>{@code ToolCallback[]}：可用工具列表，由 Spring 容器注入（包括所有 {@code @Tool} 标注的方法）</li>
 *   <li>{@code ToolCallingManager}：Spring AI 提供的工具执行管理器，负责实际调用工具方法</li>
 *   <li>{@code ChatOptions}：LLM 调用选项（如是否启用内部工具执行）</li>
 * </ul>
 *
 * @see BaseAgent 祖父类——定义 Agent 基本属性
 * @see ReActAgent 父类——定义 think/act 抽象接口
 * @see LoveManus 具体 Agent 实例（继承本类，配置了系统提示词和工具）
 */
@Data
@Slf4j
@EqualsAndHashCode(callSuper = true)  // 生成 equals/hashCode 时包含父类字段
public class ToolCallAgent extends ReActAgent {

    /**
     * Spring AI 的工具调用管理器，负责实际执行 LLM 请求的工具调用
     */
    private final ToolCallingManager toolCallingManager;

    /**
     * 当前 Agent 可用的所有工具回调（由 Spring 容器在构造时注入）
     * 包含了所有使用 {@code @Tool} 注解注册的方法
     */
    private final ToolCallback[] avilableTools;

    /**
     * think() 阶段 LLM 返回的 ChatResponse，其中包含 toolCalls 信息
     * act() 阶段会读取这个字段来执行相应的工具
     */
    private ChatResponse toolCallChatResponse;

    /**
     * LLM 调用选项配置（如温度、最大 token 数、是否启用内部工具执行等）
     */
    private final ChatOptions ChatOptions;

    /**
     * 标记是否已经将 nextStepPrompt 追加到 system prompt 中
     * 确保"下一步操作提示"只添加一次，不会在多轮对话中重复追加
     */
    private boolean nextStepPromptAdded = false;

    /**
     * 构造一个 ToolCallAgent 实例
     *
     * <p><b>初始化工作：</b></p>
     * <ol>
     *   <li>创建 ToolCallingManager（Spring AI 的默认实现）</li>
     *   <li>保存可用工具列表</li>
     *   <li>配置 ChatOptions：{@code internalToolExecutionEnabled = false}
     *       表示由应用层（而非 LLM 厂商服务端）来执行工具调用</li>
     * </ol>
     *
     * <p><b>为什么 internalToolExecutionEnabled = false？</b>
     * 因为工具是应用层自定义的（如文件操作、知识搜索等），
     * LLM 厂商（如 DashScope）无法执行这些工具，需要应用层自行处理。</p>
     *
     * @param avilableTools 由 Spring 容器注入的所有可用工具回调
     */
    public ToolCallAgent(ToolCallback[] avilableTools) {
        super();
        this.toolCallingManager = ToolCallingManager.builder().build();  // 用默认 Builder 创建
        this.avilableTools = avilableTools;
        // 配置 DashScope 的 ChatOptions：禁用服务端工具执行（工具由应用层执行）
        this.ChatOptions = DashScopeChatOptions.builder()
                .internalToolExecutionEnabled(false)  // 关键配置：不让 DashScope 服务端执行工具
                .build();
    }

    /**
     * 思考阶段：调用 LLM 分析上下文并决定是否需要调用工具
     *
     * <p><b>详细执行流程：</b></p>
     * <ol>
     *   <li>如果还没添加过 nextStepPrompt，将其追加到 system prompt 中
     *       （告知 LLM 当前应该采取什么行动策略）</li>
     *   <li>将完整的消息列表（包括对话历史）和可用工具列表发送给 LLM</li>
     *   <li>LLM 分析后返回 AssistantMessage，其中可能包含 toolCalls</li>
     *   <li>保存 LLM 返回的 ChatResponse，供后续 act() 使用</li>
     *   <li>根据是否有 toolCalls 决定是否需要执行动作</li>
     * </ol>
     *
     * <p><b>注意：</b>此方法使用 {@code .call()}（同步阻塞调用），
     * 而 stream 场景下使用 {@link #streamThink(SseEmitter)}（流式调用）。</p>
     *
     * @return {@code true} = 需要执行工具（有 toolCalls）；
     *         {@code false} = LLM 直接返回了文本答案，无需进一步行动
     */
    @Override
    public boolean think() {
        try {
            // 构建额外的系统提示——如果需要的话，将 nextStepPrompt 追加进去
            String extraSystem = "";
            if (!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())) {
                extraSystem = "\n" + getNextStepPrompt();  // nextStepPrompt 通常是工具使用指引
                nextStepPromptAdded = true;                // 标记已添加，防止重复
            }

            List<Message> messageList = getMessageList();  // 获取当前对话历史
            Prompt prompt = new Prompt(messageList, ChatOptions);  // 构建 Prompt

            // 关键调用：通过 ChatClient 向 LLM 发送请求
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt() + extraSystem)     // 设置系统提示词（身份+行为指南）
                    .toolCallbacks(avilableTools)                 // 告知 LLM 有哪些工具可用
                    .call()                                       // 同步调用，等待 LLM 返回
                    .chatResponse();                              // 获取响应

            this.toolCallChatResponse = chatResponse;  // 保存供 act() 使用
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();  // LLM 的回复
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();  // LLM 请求调用的工具

            log.info("result: {}, toolCalls: {}", assistantMessage.getText(), toolCallList);

            // 判断：如果 LLM 没有请求任何工具，说明它已经给出了最终答案
            if (toolCallList == null || toolCallList.isEmpty()) {
                getMessageList().add(assistantMessage);  // 将 LLM 的最终回复加入消息历史
                return false;  // 不需要执行 act
            }
            return true;  // 有工具需要调用，需要执行 act
        } catch (Exception e) {
            log.error("Error: {}", e.getMessage());
            // 如果 LLM 调用本身出错，仍然将错误消息加入历史（让 Agent 有机会感知并处理错误）
            getMessageList().add(new AssistantMessage(e.getMessage()));
            return false;  // 出错时不执行工具
        }
    }

    /**
     * <b>流式思考</b>——与 think() 逻辑相同，但使用 SSE 流式将文本实时推送给前端
     *
     * <p><b>与 think() 的关键区别：</b></p>
     * <ul>
     *   <li>使用 {@code .stream()} 代替 {@code .call()}，LLM 输出的文本会逐 token 推送</li>
     *   <li>通过 {@code emitter.send(text)} 实时将文本推送到 SSE 通道</li>
     *   <li>只有<b>纯文本部分</b>被流式推送（toolCalls 的 delta 不推送，因为对用户无意义）</li>
     *   <li>最后收集完整的 toolCalls 信息，供 act() 使用</li>
     * </ul>
     *
     * <p><b>流式响应收集策略：</b>流式响应是分片（chunk）到达的，每个 chunk 可能包含
     * 文本片段或工具调用片段。这里收集所有 chunk，然后从最后一个包含 toolCalls 的
     * chunk 中提取完整的工具调用列表。</p>
     *
     * @param emitter SSE 发射器，用于向前端实时推送文本
     * @return {@code true} = 有工具需要调用；{@code false} = LLM 完成了最终回答
     */
    public boolean streamThink(SseEmitter emitter) {
        try {
            // 构建系统提示（与 think() 相同）
            String extraSystem = "";
            if (!nextStepPromptAdded && StrUtil.isNotBlank(getNextStepPrompt())) {
                extraSystem = "\n" + getNextStepPrompt();
                nextStepPromptAdded = true;
            }

            List<Message> messageList = getMessageList();
            Prompt prompt = new Prompt(messageList, ChatOptions);

            List<ChatResponse> responses = new ArrayList<>();  // 收集所有流式响应片段
            StringBuilder fullText = new StringBuilder();      // 累积完整的流式文本

            // 流式调用 LLM
            getChatClient().prompt(prompt)
                    .system(getSystemPrompt() + extraSystem)
                    .toolCallbacks(avilableTools)
                    .stream()                           // 启动流式调用（Reactor Flux）
                    .chatResponse()                     // 返回 Flux<ChatResponse>
                    .doOnNext(response -> {             // 每收到一个数据块就执行
                        responses.add(response);
                        org.springframework.ai.chat.model.Generation result = response.getResult();
                        if (result == null || result.getOutput() == null) {
                            return; // 无文本块（如仅元数据/工具调用结束标记），跳过——修复 getResult null NPE
                        }
                        AssistantMessage msg = result.getOutput();
                        if (msg != null) {
                            // 关键判断：区分文本 delta 和工具调用 delta
                            // - 文本 delta：msg.getText() 有值，msg.getToolCalls() 为空 → 流式推送给前端
                            // - 工具调用 delta：msg.getToolCalls() 有值 → 不推送，留到 act() 处理
                            boolean hasToolCalls = msg.getToolCalls() != null
                                    && !msg.getToolCalls().isEmpty();
                            String text = msg.getText();
                            if (text != null && !text.isEmpty() && !hasToolCalls) {
                                fullText.append(text);     // 累积文本（用于构建完整的 AssistantMessage）
                                try {
                                    emitter.send(text);     // 实时推送给前端（SSE 逐 token 发送）
                                } catch (IOException e) {
                                    log.warn("SSE send failed: {}", e.getMessage());
                                }
                            }
                        }
                    })
                    .blockLast();  // 阻塞等待流式响应全部完成

            // 流式响应完成后，从最后一个包含 toolCalls 的响应中提取工具调用信息
            ChatResponse lastResp = null;
            for (int ri = responses.size() - 1; ri >= 0; ri--) {
                org.springframework.ai.chat.model.Generation r = responses.get(ri).getResult();
                if (r == null || r.getOutput() == null) {
                    continue;
                }
                AssistantMessage m = r.getOutput();
                if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                    lastResp = responses.get(ri);  // 找到最后一个包含 toolCalls 的响应
                    break;
                }
            }
            if (lastResp == null && !responses.isEmpty()) {
                lastResp = responses.get(responses.size() - 1);  // 没有 toolCalls，取最后一个响应
            }
            this.toolCallChatResponse = lastResp;  // 保存供 act() 使用

            // 判断是否有工具需要调用
            List<AssistantMessage.ToolCall> toolCalls = lastResp != null
                    && lastResp.getResult().getOutput() != null
                    ? lastResp.getResult().getOutput().getToolCalls() : null;
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 没有工具调用，LLM 已完成回答 → 将累积的文本作为 AssistantMessage 加入历史
                getMessageList().add(new AssistantMessage(fullText.toString()));
                return false;  // 不需要执行 act
            }
            return true;  // 有工具需要调用

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

    /**
     * 重置 Agent 状态，准备下一轮多轮对话
     *
     * <p>除了调用父类的 reset（重置状态和步数），还需要重置 {@code nextStepPromptAdded} 标志，
     * 确保下一轮对话时 nextStepPrompt 能重新被添加到 system prompt 中。</p>
     */
    @Override
    public void resetForNextTurn() {
        super.resetForNextTurn();          // 父类：state → IDLE, currentStep → 0
        this.nextStepPromptAdded = false;  // 本类：重置提示词添加标志
    }

    /**
     * 执行阶段：实际调用 LLM 请求的工具
     *
     * <p><b>执行流程：</b></p>
     * <ol>
     *   <li>检查 think()/streamThink() 返回的 ChatResponse 是否包含工具调用</li>
     *   <li>如果没有工具调用，返回提示信息</li>
     *   <li>通过 ToolCallingManager 执行每个被调用的工具</li>
     *   <li>将工具执行结果更新到消息列表（替换旧的上下文）</li>
     *   <li>判断是否调用了 terminate 工具——如果是，标记任务完成</li>
     *   <li>将所有工具结果拼接为字符串返回</li>
     * </ol>
     *
     * <p><b>消息列表更新机制：</b>{@code toolCallingManager.executeToolCalls()} 会返回
     * 更新后的完整 conversationHistory（已将工具执行结果附加上去）。
     * 这里用 {@code setMessageList()} 整体替换。</p>
     *
     * <p><b>terminate 工具的特殊处理：</b>terminate 是一个"标记任务结束"的工具，
     * 它本身不执行实质性操作，但调用它表示 LLM 认为任务已完成，Agent 应该停止循环。</p>
     *
     * @return 工具执行结果的字符串表示，格式为 "tools{工具名}result: {结果}"
     */
    @Override
    public String act() {
        // 安全检查：如果 ChatResponse 中没有工具调用，无需执行
        if (!toolCallChatResponse.hasToolCalls()) {
            return "no tools need use";
        }

        // 通过 ToolCallingManager 执行所有工具调用
        // executeToolCalls 内部会：
        // 1. 解析 toolCalls 中的工具名和参数
        // 2. 反射调用对应的 Java 方法
        // 3. 将结果封装为 ToolResponseMessage 返回
        Prompt prompt = new Prompt(getMessageList(), this.ChatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);

        // 用工具执行后的更新版本替换消息列表（原有历史 + 工具调用结果）
        setMessageList(toolExecutionResult.conversationHistory());

        // 获取最后一条消息（即工具执行后的 ToolResponseMessage）
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());

        // 检查是否调用了 terminate 工具——表示任务即将结束
        boolean isTerminate = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> response.name().equals("doTerminate"));  // TerminateTool 的方法名
        if (isTerminate) {
            setState(AgentState.FINISHED);  // 标记 Agent 状态为完成，下一轮 step 循环将退出
        }

        // 将所有工具执行结果拼接为一个字符串
        String result = toolResponseMessage.getResponses().stream()
                .map(response -> "tools" + response.name() + "result: " + response.responseData())
                .collect(Collectors.joining("\n"));

        log.info("act result: {}", result);
        return result;
    }
}
