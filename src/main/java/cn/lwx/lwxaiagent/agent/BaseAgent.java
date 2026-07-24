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
 * @description: Base Agent class, parent of all concrete Agent types
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

    // MySQL persistent memory (optional, injected via AiController)
    private ChatMemory chatMemory;
    private String conversationId;

    public SseEmitter runStream(String userPrompt) {
        SseEmitter emitter = new SseEmitter(600000L); // Set timeout to 10 minutes

        CompletableFuture.runAsync(()->{// Use CompletableFuture for async execution to avoid blocking the main thread
            // Check state and prompt, basic validation
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
            // Set state to running
            this.state = AgentState.RUNNING;

            // Load history from MySQL (only when messageList is empty, to avoid duplicate loading when reusing Agent)
            if (chatMemory != null && conversationId != null && messageList.isEmpty()) {
                List<Message> history = chatMemory.get(conversationId);
                if (history != null && !history.isEmpty()) {
                    // Filter out system internal prompts (old data may remain)
                    history.removeIf(msg ->
                        msg instanceof UserMessage &&
                        ((UserMessage) msg).getText().contains("You have tools available"));
                    messageList.addAll(0, history);
                }
            }

            // Record prompt context
            messageList.add(new UserMessage(userPrompt));
            // Save results list
            List<String> results = new ArrayList<>();
            // Accumulate file output
            StringBuilder pendingFileOutput = new StringBuilder();
            Set<String> seenFileOutputs = new HashSet<>();

            // Step execution loop, until completion or error
            try {
                for (int i = 0; i < maxSteps && this.state == AgentState.RUNNING; i++) {
                    int stepNumber = i + 1;
                    boolean toolsCalled;
                    String stepResult;

                    // --- Streaming think: use .stream() to output tokens in real time, replacing step()'s blocking .call() ---
                    if (this instanceof ToolCallAgent tca) {
                        toolsCalled = tca.streamThink(emitter);
                        if (!toolsCalled) {
                            // Final answer already streamed by streamThink, clear accumulated file output to avoid duplication
                            pendingFileOutput.setLength(0);
                            this.state = AgentState.FINISHED;
                            break;
                        }
                        // Tools need to be called: execute act
                        stepResult = tca.act();
                    } else {
                        stepResult = step();
                        toolsCalled = isToolsCalled();
                    }
                    this.currentStep = stepNumber;
                    // The following only handles display of tool call steps (💭), final answer already streamed by streamThink
                    String thought = extractLastThought();
                    if (thought != null) {
                        thought = thought.replace("\\n", "\n").replace("\\t", "\t");
                    }

                    // Accumulate file output (for final result display only)
                    String fileOutput = extractFileOutput(stepResult, seenFileOutputs);
                    if (fileOutput != null) {
                        if (pendingFileOutput.length() > 0) pendingFileOutput.append("\n");
                        pendingFileOutput.append(fileOutput);
                    }

                    // Skip tool execution steps without thought content
                    if (thought == null || thought.trim().isEmpty()) {
                        continue;
                    }

                    String text = thought != null ? removeUrlLines(thought) : "";
                    if (!text.isEmpty()) {
                        emitter.send("💭 " + text);
                    }

                }
                // Loop end: if there is unshown file output (e.g., terminate tool was called), send it
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
                // Send completion state after finish
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
                // Persist conversation history to MySQL
                if (chatMemory != null && conversationId != null && !messageList.isEmpty()) {
                    try {
                        chatMemory.clear(conversationId);
                        // Filter: only keep user messages and non-empty assistant replies, do not save internal messages like tool calls
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
        // Set timeout callback
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
     * External stop for Agent execution: sets state to FINISHED, loop exits after current step ends
     */
    public void stop() {
        this.state = AgentState.FINISHED;
    }

    /**
     * Reset state for multi-turn conversation: clear step count, restore state to IDLE, but keep messageList (conversation history).
     * Ensure the previous turn has ended (state == FINISHED) before calling.
     */
    public void resetForNextTurn() {
        this.state = AgentState.IDIE;
        this.currentStep = 0;
    }

    /**
     * Extract the latest AssistantMessage reasoning content from the message list (DeepSeek's actual reasoning).
     * Priority: DeepSeekAssistantMessage.getReasoningContent(), fallback to getText().
     * <p>
     * If reasoning is mostly English (DeepSeek V4's CoT is in English), do not display, return null.
     */
    private String extractLastThought() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof DeepSeekAssistantMessage) {
                String r = ((DeepSeekAssistantMessage) msg).getReasoningContent();
                if (r != null && !r.isBlank()) {
                    if (isMostlyEnglish(r)) {
                        return null; // English CoT not displayed, skip
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

    /** Check if text is mostly English (non-Chinese content ratio > 80%) */
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
     * Extract the latest AssistantMessage visible text content (getText), distinct from reasoningContent
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
     * Check if the current step called a tool (messageList has ToolResponseMessage at the end)
     */
    private boolean isToolsCalled() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            if (messageList.get(i) instanceof ToolResponseMessage) {
                return true;
            }
            // If encountering AssistantMessage (just finished thinking, hasn't executed tool yet), stop searching upward
            if (messageList.get(i) instanceof AssistantMessage) {
                return false;
            }
        }
        return false;
    }

    /**
     * Extract accessible file URLs from stepResult (links containing /api/), skip already included paths
     */
    private String extractFileOutput(String stepResult, Set<String> seenOutputs) {
        if (stepResult == null || stepResult.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        // JSON serialization escapes \n to literal \\n, restore first
        stepResult = stepResult.replace("\\n", "\n");
        for (String line : stepResult.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("\"")) {
                continue;
            }
            // Only capture lines containing accessible file URLs
            if (trimmed.contains("/api/")) {
                // Remove "toolsXXXresult:" prefix
                int idx = trimmed.indexOf("result:");
                String info = (idx >= 0) ? trimmed.substring(idx + 7).trim() : trimmed;
                // Remove redundant 📄 prefix at line start
                info = info.replaceAll("^📄\\s*", "");
                // Deduplication: skip if this path has already been shown
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
     * Convert local file path (e.g. D:\path\to\downloads\file.png) to accessible HTTP URL (/api/files/downloads/file.png)
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
     * Remove URL lines and tool execution status lines from thought content (meaningless to users)
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
            // Skip error analysis and self-correction (meaningless to users)
            if (trimmed.startsWith("The error")) continue;
            if (trimmed.startsWith("Let me remove")) continue;
            if (trimmed.startsWith("I should avoid")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
