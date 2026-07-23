package cn.lwx.lwxaiagent.agent;

import cn.hutool.core.util.StrUtil;
import cn.lwx.lwxaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * @description: 基础Agent类，所有具体Agent类型的父类
 *
 */
@Slf4j
@Data
public abstract class BaseAgent {
    //定义核心属性
    private String name; //Agent名称

    //提示词
    private String systemPrompt;
    //下一步提示
    private String nextStepPrompt;

    //代理状态
    private AgentState state = AgentState.IDIE;

    //步骤控制
    private int currentStep = 0;
    private int maxSteps = 15;

    //定义LLM
    private ChatClient chatClient;

    //上下文记忆用springai的List
    private List<Message> messageList = new ArrayList<>();

    // MySQL 持久化记忆（可选，通过 AiController 注入）
    private ChatMemory chatMemory;
    private String conversationId;

    public SseEmitter runStream(String userPrompt) {
        SseEmitter emitter = new SseEmitter(600000L); //设置超时时间为10分钟

        CompletableFuture.runAsync(()->{//使用CompletableFuture异步执行，避免阻塞主线程
            //检查状态和提示词，基础校验
            if (this.state!= AgentState.IDIE) {
                try {
                    if (this.state!= AgentState.IDIE) {
                        emitter.send("SSE: Can not run agent that is not in"+ this.state);
                        emitter.complete();
                        return;
                    }
                    if(StrUtil.isBlank(this.systemPrompt)){
                        emitter.send("SSE: System prompt is blank, can not run agent");
                        emitter.complete();
                        return;
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
                emitter.complete();
                return;
            }
            //设置状态为运行中
            this.state = AgentState.RUNNING;

            // 从 MySQL 加载历史记忆（仅 messageList 为空时，避免复用 Agent 重复加载）
            if (chatMemory != null && conversationId != null && messageList.isEmpty()) {
                List<Message> history = chatMemory.get(conversationId);
                if (history != null && !history.isEmpty()) {
                    // 过滤掉系统内部提示（旧数据可能残留）
                    history.removeIf(msg ->
                        msg instanceof UserMessage &&
                        ((UserMessage) msg).getText().contains("You have tools available"));
                    messageList.addAll(0, history);
                }
            }

            //记录信息上下文
            messageList.add(new UserMessage(userPrompt));
            //保存结果列表
            List<String> results = new ArrayList<>();
            // 累积文件输出
            StringBuilder pendingFileOutput = new StringBuilder();
            Set<String> seenFileOutputs = new HashSet<>();

            //步骤执行循环，直到完成或出错
            try {
                for (int i = 0; i < maxSteps && this.state == AgentState.RUNNING; i++) {
                    int stepNumber = i + 1;
                    boolean toolsCalled;
                    String stepResult;

                    // --- 流式思考：用 .stream() 实时输出 token，替代 step() 的阻塞 .call() ---
                    if (this instanceof ToolCallAgent tca) {
                        toolsCalled = tca.streamThink(emitter);
                        if (!toolsCalled) {
                            // 最终答案已由 streamThink 流式发送，清空已累积的文件输出避免重复
                            pendingFileOutput.setLength(0);
                            this.state = AgentState.FINISHED;
                            break;
                        }
                        // 需要工具：执行 act
                        stepResult = tca.act();
                    } else {
                        stepResult = step();
                        toolsCalled = isToolsCalled();
                    }
                    this.currentStep = stepNumber;
                    // 以下仅处理工具调用步骤的展示（💭），最终答案已由 streamThink 流式发送
                    String thought = extractLastThought();
                    if (thought != null) {
                        thought = thought.replace("\\n", "\n").replace("\\t", "\t");
                    }

                    // 累积文件输出（仅用于最终结果展示）
                    String fileOutput = extractFileOutput(stepResult, seenFileOutputs);
                    if (fileOutput != null) {
                        if (pendingFileOutput.length() > 0) pendingFileOutput.append("\n");
                        pendingFileOutput.append(fileOutput);
                    }

                    // 跳过无思考内容的工具执行步骤
                    if (thought == null || thought.trim().isEmpty()) {
                        continue;
                    }

                    String text = thought != null ? removeUrlLines(thought) : "";
                    if (!text.isEmpty()) {
                        emitter.send("💭 " + text);
                    }

                }
                // 循环结束：如果还有未展示的文件输出（如调用了 terminate 工具结束），补发
                if (pendingFileOutput.length() > 0) {
                    String files = pendingFileOutput.toString()
                        .replaceAll("/api/files/downloads/[^\\s)\"]+\\.(png|jpg|jpeg|gif|webp)\\b", "![]($0)");
                    emitter.send("\n✨ " + files);
                }
                if (currentStep >= maxSteps) {
                    this.state = AgentState.FINISHED;
                    results.add("Agent reached max steps:" + maxSteps);
                    emitter.send("Agent reached max steps:" + maxSteps);
                }
                //完成后发送完成状态
                emitter.complete();
            } catch (Exception e) {
                this.state = AgentState.ERROR;
                log.error("Agent {} encountered an error at step {}: {}", this.name, currentStep + 1, e.getMessage());
                try {
                    emitter.send("Agent encountered an error: " + e.getMessage());
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
                //emitter.complete();
            }
            finally {
                // 持久化对话历史到 MySQL
                if (chatMemory != null && conversationId != null && !messageList.isEmpty()) {
                    try {
                        chatMemory.clear(conversationId);
                        // 过滤：只保留用户消息和非空助手回复，不保存工具调用等内部消息
                        List<Message> persistentMessages = messageList.stream()
                                .filter(m -> {
                                    if (m instanceof UserMessage) return true;
                                    if (m instanceof AssistantMessage) {
                                        return m.getText() != null && !m.getText().isBlank();
                                    }
                                    return false;
                                })
                                .collect(java.util.stream.Collectors.toList());
                        if (!persistentMessages.isEmpty()) {
                            chatMemory.add(conversationId, persistentMessages);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to persist chat memory: {}", e.getMessage());
                    }
                }
                this.cleanup();
            }
        });
        //设置超时回调
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSEEmitter for agent timed out.");
            emitter.complete();
        });
        emitter.onCompletion(() -> {
            log.info("SSEEmitter for agent completed.");
        });
        return emitter;
    }

    abstract public String step();

    public void cleanup(){};

    /**
     * 外部停止 Agent 执行：设置状态为 FINISHED，循环会在当前 step 结束后退出
     */
    public void stop() {
        this.state = AgentState.FINISHED;
    }

    /**
     * 重置状态以支持多轮对话：清空步骤计数，状态恢复 IDLE，但保留 messageList（对话历史）
     * 调用前需确保前一轮已结束（state == FINISHED）
     */
    public void resetForNextTurn() {
        this.state = AgentState.IDIE;
        this.currentStep = 0;
    }

    /**
     * 从消息列表中提取最新的 AssistantMessage 推理内容（DeepSeek 的 actual reasoning）
     * 优先取 DeepSeekAssistantMessage.getReasoningContent()，fallback 到 getText()
     * <p>
     * 如果 reasoning 主要为英文（DeepSeek V4 的 CoT 用英文），则不展示，返回简短中文标签。
     */
    private String extractLastThought() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof DeepSeekAssistantMessage) {
                String r = ((DeepSeekAssistantMessage) msg).getReasoningContent();
                if (r != null && !r.isBlank()) {
                    if (isMostlyEnglish(r)) {
                        return null; // 英文 CoT 不展示，跳过
                    }
                    return r;
                }
                return ((AssistantMessage) msg).getText();
            }
            if (msg instanceof AssistantMessage) {
                String text = ((AssistantMessage) msg).getText();
                if (text != null && isMostlyEnglish(text)) {
                    return null;
                }
                return text;
            }
        }
        return null;
    }

    /** 判断文本是否主要是英文（非中文内容占比 &gt; 80%） */
    private boolean isMostlyEnglish(String text) {
        if (text == null || text.isBlank()) return false;
        int total = 0, chinese = 0;
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) continue;
            total++;
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                chinese++;
            }
        }
        return total > 0 && (double) chinese / total < 0.2;
    }

    /**
     * 提取最新 AssistantMessage 的可视文本内容（getText），与 reasoningContent 区分
     */
    private String extractLastText() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof AssistantMessage) {
                return ((AssistantMessage) msg).getText();
            }
        }
        return null;
    }

    /**
     * 判断当前步骤是否调用了工具（messageList 末尾有 ToolResponseMessage）
     */
    private boolean isToolsCalled() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            if (messageList.get(i) instanceof ToolResponseMessage) {
                return true;
            }
            // 如果遇到 AssistantMessage（说明刚思考完还没执行工具），停止向上查找
            if (messageList.get(i) instanceof AssistantMessage) {
                return false;
            }
        }
        return false;
    }

    /**
     * 从 stepResult 中提取可访问的文件 URL（包含 /api/ 的链接），已包含的路径跳过
     */
    private String extractFileOutput(String stepResult, Set<String> seenOutputs) {
        if (stepResult == null || stepResult.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        // JSON 序列化会将 \n 转义为 literal \\n，先还原
        stepResult = stepResult.replace("\\n", "\n");
        for (String line : stepResult.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("\"")) {
                continue;
            }
            // 只捕获包含可访问文件 URL 的行
            if (trimmed.contains("/api/")) {
                // 去掉 "toolsXXXresult:" 前缀
                int idx = trimmed.indexOf("result:");
                String info = (idx >= 0) ? trimmed.substring(idx + 7).trim() : trimmed;
                // 去掉行首的冗余 📄 前缀
                info = info.replaceAll("^📄\\s*", "");
                // 去重：如果这个路径已经展示过，跳过
                if (!seenOutputs.add(info)) {
                    continue;
                }
                if (sb.length() > 0) sb.append("\n");
                sb.append(info);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 将本地文件路径（如 D:\path\to\downloads\file.png）转为可访问的 HTTP URL（/api/files/downloads/file.png）
     */
    private String removeLocalPaths(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text.replaceAll("[a-zA-Z]:[\\\\/].*?downloads[\\\\/]", "/api/files/downloads/");
        result = result.replaceAll(
                "(?<!\\()/api/files/downloads/[^\\s)\"]+\\.(png|jpg|jpeg|gif|webp)\\b",
            "![]($0)"
        );
        return result;
    }

    /**
     * 移除思考内容中的 URL 行和工具执行状态行（对用户无意义）
     */
    private String removeUrlLines(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("http://") || trimmed.contains("https://")) continue;
            if (trimmed.matches(".*\\d+\\s*张.*(?:成功|失败).*")) continue;
            if (trimmed.matches(".*[A-Za-z]:[/\\\\].*")) continue;
            // 跳过错误分析和自我修正（对用户无意义）
            if (trimmed.startsWith("The error")) continue;
            if (trimmed.startsWith("Let me remove")) continue;
            if (trimmed.startsWith("I should avoid")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
