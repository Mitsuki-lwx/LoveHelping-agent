package cn.lwx.lwxaiagent.agent;

import cn.hutool.core.util.StrUtil;
import cn.lwx.lwxaiagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

            //记录信息上下文
            messageList.add(new UserMessage(userPrompt));
            //保存结果列表
            List<String> results = new ArrayList<>();
            // 累积文件输出（仅在最终结果展示）
            StringBuilder pendingFileOutput = new StringBuilder();
            //步骤执行循环，直到完成或出错
            try {
                for (int i = 0; i < maxSteps&& this.state == AgentState.RUNNING; i++) {
                    int stepNumber = i + 1;
                    log.info("Agent {} executing step {}/{}", this.name, stepNumber, maxSteps);
                    //执行单步骤并记录结果
                    String stepResult = step();
                    this.currentStep = stepNumber;
                    // 从消息列表中提取最新的思考文本，并判断是否调用了工具
                    String thought = extractLastThought();
                    // 清理 AI 输出中的转义字符（如 \n → 真实换行）
                    if (thought != null) {
                        thought = thought.replace("\\n", "\n").replace("\\t", "\t");
                    }
                    boolean toolsCalled = isToolsCalled();

                    // 累积文件输出（仅用于最终结果展示）
                    String fileOutput = extractFileOutput(stepResult);
                    if (toolsCalled && fileOutput != null) {
                        if (pendingFileOutput.length() > 0) pendingFileOutput.append("\n");
                        pendingFileOutput.append(fileOutput);
                    }

                    // 跳过无思考内容的工具执行步骤（空要点）
                    if (toolsCalled && (thought == null || thought.trim().isEmpty())) {
                        continue;
                    }

                    String displayText;
                    if (toolsCalled) {
                        // 中间思考步骤：如果本轮生成了文件，直接展示（避免 AI 持续调工具导致永不进入最终结果分支）
                        StringBuilder sb = new StringBuilder("💭 ");
                        sb.append(thought != null ? thought : "");
                        if (fileOutput != null) {
                            sb.append("\n\n").append(fileOutput);
                        }
                        displayText = sb.toString();
                    } else {
                        // 最终答案：追加累积的文件输出
                        StringBuilder sb = new StringBuilder("✨ ");
                        sb.append(thought != null ? thought : stepResult);
                        if (pendingFileOutput.length() > 0) {
                            sb.append("\n\n").append(pendingFileOutput);
                        }
                        displayText = sb.toString();
                    }
                    // 清理输出：移除本地文件系统路径，折叠多余换行
                    displayText = removeLocalPaths(displayText);
                    displayText = displayText.replaceAll("\\n{3,}", "\n\n");
                    results.add("Step " + stepNumber + " result: " + stepResult);
                    //输出思考过程给sseEmitter，而不是原始工具数据
                    emitter.send("\n" + displayText);

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
        emitter.onCompletion(() -> {//设置完成回调
            if(this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSEEmitter for agent completed.");
        });
        return emitter;
    }

    abstract public String step();

    public void cleanup(){};

    /**
     * 从消息列表中提取最新的 AssistantMessage 文本（即模型的思考过程）
     */
    private String extractLastThought() {
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
     * 从 stepResult 中提取可访问的文件 URL（包含 /api/ 的链接）
     */
    private String extractFileOutput(String stepResult) {
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
                if (sb.length() > 0) sb.append("\n");
                sb.append(info);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 移除文本中的本地文件系统路径（如 D:\path\to\file.pdf），只保留可访问的 URL
     */
    private String removeLocalPaths(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            // 如果行包含 Windows 盘符路径（如 D:\...），跳过该行
            if (line.matches(".*[a-zA-Z]:\\\\.*")) {
                continue;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }
}
