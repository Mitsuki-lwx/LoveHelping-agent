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
 * <h1>所有 Agent 的抽象基类</h1>
 *
 * <p>BaseAgent 定义了 Agent 的<b>全部核心属性和运行框架</b>，是整个 Agent 继承树的根节点。</p>
 *
 * <h2>核心属性</h2>
 * <ul>
 *   <li><b>身份标识</b>：name（Agent 名称）</li>
 *   <li><b>提示词</b>：systemPrompt（定义 Agent 身份和行为）、nextStepPrompt（指导工具使用）</li>
 *   <li><b>状态管理</b>：state（IDLE → RUNNING → FINISHED/ERROR）</li>
 *   <li><b>步数控制</b>：currentStep（当前步数）、maxSteps（最大步数，防止无限循环）</li>
 *   <li><b>对话管理</b>：messageList（内存中的对话历史）、chatMemory（MySQL 持久化）、conversationId（会话标识）</li>
 *   <li><b>LLM 客户端</b>：chatClient（Spring AI 的 ChatClient 封装）</li>
 * </ul>
 *
 * <h2>核心方法：runStream()</h2>
 * <p>这是 Agent 的<b>唯一入口</b>——外部调用此方法启动 Agent 执行：</p>
 * <ol>
 *   <li>创建 SSE（Server-Sent Events）连接，用于实时向前端推送 Agent 的思考和执行过程</li>
 *   <li>在异步线程中执行 Step 循环（每步调用 step()，即 think + act）</li>
 *   <li>每步提取 LLM 的思考内容（reasoningContent）通过 SSE 推送给前端</li>
 *   <li>循环结束后持久化对话历史到 MySQL</li>
 * </ol>
 *
 * <h2>类继承结构</h2>
 * <pre>
 *   BaseAgent（本类）
 *     ↑ 抽象 step()
 *   ReActAgent
 *     ↑ 实现 step()，抽象 think() 和 act()
 *   ToolCallAgent
 *     ↑ 实现 think() 和 act()，添加工具调用能力
 *   LoveManus
 *     ↑ 配置系统提示词、模型、工具列表
 * </pre>
 *
 * @author lwx
 * @see ReActAgent 直接子类
 * @see AgentState Agent 状态枚举
 */
@Slf4j  // Lombok：自动生成 log 字段（SLF4J 日志）
@Data   // Lombok：自动生成 getter/setter/toString/equals/hashCode
public abstract class BaseAgent {

    // ==================== 核心属性定义 ====================

    /** Agent 名称（如 "LoveManus"），用于日志和调试标识 */
    private String name;

    /**
     * 系统提示词（System Prompt）——定义 Agent 的身份、能力边界、行为风格
     *
     * <p>在每次 LLM 调用时会作为 system message 传入，是 Agent 最核心的"人格"定义。
     * 子类（LoveManus）在构造器中设置此字段。</p>
     */
    private String systemPrompt;

    /**
     * 下一步操作提示——在第一次 LLM 调用时追加到 system prompt 中
     *
     * <p>通常包含工具使用指引，例如："你有工具可用，复杂任务请分解为步骤"。
     * 只在第一轮 think 时添加，后续轮次不再重复（由 ToolCallAgent.nextStepPromptAdded 控制）。</p>
     */
    private String nextStepPrompt;

    /**
     * Agent 当前状态，初始为 IDLE（空闲等待）
     */
    private AgentState state = AgentState.IDIE;

    /**
     * 当前已执行的步数（每完成一次 think+act 循环计为一步）
     */
    private int currentStep = 0;

    /**
     * 最大执行步数——防止 Agent 陷入无限循环的安全限制
     * 默认 15 步，超过后强制结束（state → FINISHED）
     */
    private int maxSteps = 15;

    /**
     * Spring AI 的聊天客户端——封装了 ChatModel，提供流式调用、Advisor 链等高级能力
     */
    private ChatClient chatClient;

    /**
     * 对话消息列表（内存中）——LLM 的上下文窗口
     *
     * <p>每条消息可以是：</p>
     * <ul>
     *   <li>{@link UserMessage}：用户输入</li>
     *   <li>{@link AssistantMessage}：LLM 回复（可能包含文本或 toolCalls）</li>
     *   <li>{@link ToolResponseMessage}：工具执行结果</li>
     * </ul>
     *
     * <p>每次 LLM 调用都会将整个 messageList 作为上下文发送，
     * 所以 messageList 的大小直接影响 token 消耗和上下文长度。</p>
     */
    private List<Message> messageList = new ArrayList<>();

    /**
     * 聊天记忆持久化（MySQL）
     *
     * <p>通过 Spring AI 的 ChatMemory 接口实现，在多轮对话之间保存历史消息。
     * 如果不为 null，每次对话结束后会将 messageList 持久化到数据库，
     * 下次对话开始时再加载回来，实现跨会话的记忆延续。</p>
     */
    private ChatMemory chatMemory;

    /**
     * 任务完成回调（ADR-3）：在 SSE 流结束（正常/异常）时同步调用，用于任务状态收尾。
     * 参数：(success, errorMsg)。由调用方（ChatService）注入。
     */
    private java.util.function.BiConsumer<Boolean, String> completionCallback;

    public void setCompletionCallback(java.util.function.BiConsumer<Boolean, String> callback) {
        this.completionCallback = callback;
    }

    /**
     * 会话 ID——标识一次完整的对话会话
     *
     * <p>用于 ChatMemory 的 key，同一个 conversationId 的对话共享上下文。
     * 不同会话（如不同用户、不同话题）应使用不同的 conversationId。</p>
     */
    private String conversationId;

    /**
     * <h2>启动 Agent 的流式执行（核心入口方法）</h2>
     *
     * <p>这是外部调用 Agent 的<b>唯一入口</b>。Controller 层调用此方法，传入用户输入的文本，
     * 返回一个 SSE（Server-Sent Events）连接对象，通过该连接实时推送 Agent 的执行过程。</p>
     *
     * <h3>整体架构</h3>
     * <pre>
     *   Controller（AiController.chatStream）
     *       │
     *       ▼ 调用 runStream(userPrompt)
     *   BaseAgent.runStream()
     *       │
     *       ├── 创建 SseEmitter（超时 10 分钟）
     *       │
     *       └── CompletableFuture.runAsync（异步执行，不阻塞 Controller 线程）
     *               │
     *               ├── 1. 状态校验（是否 IDLE，提示词是否为空）
     *               ├── 2. 从 MySQL 加载历史对话（如果有 ChatMemory）
     *               ├── 3. 记录用户输入到 messageList
     *               │
     *               ├── 4. Step 循环（最多 maxSteps 步）
     *               │      │
     *               │      ├── streamThink(emitter) —— 流式调用 LLM，实时推送文本
     *               │      │      └── 如果有 toolCalls → 继续
     *               │      │      └── 如果没有 toolCalls → 结束循环
     *               │      │
     *               │      ├── act() —— 执行 LLM 请求的工具
     *               │      │
     *               │      └── emitter.send("💭 " + thought) —— 推送思考过程
     *               │
     *               ├── 5. 推送文件输出（如 PDF、图片等生成的文件链接）
     *               │
     *               └── 6. finally：持久化对话到 MySQL，清理资源
     *   </pre>
     *
     * <h3>为什么用 SSE 而不是 WebSocket？</h3>
     * <ul>
     *   <li>SSE 是单向的（服务器 → 客户端），正好满足 Agent 输出流的场景</li>
     *   <li>SSE 基于 HTTP，穿透代理/防火墙更友好，浏览器原生支持 EventSource API</li>
     *   <li>WebSocket 是双向的，但在 Agent 场景中不需要客户端频繁向服务器推送数据</li>
     * </ul>
     *
     * <h3>异步执行的原因</h3>
     * <p>CompletableFuture.runAsync 确保 Agent 执行在独立的线程中进行，</p>
     * <ul>
     *   <li>Controller 方法立即返回 SseEmitter（不阻塞 HTTP 线程）</li>
     *   <li>Agent 执行期间，SSE 连接保持打开状态</li>
     *   <li>Tomcat 的 HTTP 线程池不会被 Agent 长时间占用</li>
     * </ul>
     *
     * @param userPrompt 用户输入的文本（如 "帮我写一封情书"）
     * @return SseEmitter SSE 连接对象，前端通过 EventSource 接收实时推送
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建 SSE 发射器，超时时间设为 10 分钟（600000 毫秒）
        // 如果 Agent 执行超过 10 分钟还没有 complete，连接会被自动断开
        SseEmitter emitter = new SseEmitter(600000L);

        // 使用 CompletableFuture 异步执行 Agent 主循环，避免阻塞调用线程
        CompletableFuture.runAsync(() -> {
            // ---------- 阶段 1：前置校验 ----------
            if (this.state != AgentState.IDIE) {
                try {
                    if (this.state != AgentState.IDIE) {
                        emitter.send("SSE: Can not run agent that is not in" + this.state);
                        emitter.complete();
                        return;
                    }
                    if (StrUtil.isBlank(this.systemPrompt)) {
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

            // 将状态设置为 RUNNING——此后其他线程无法再启动此 Agent
            this.state = AgentState.RUNNING;

            // ---------- 阶段 2：加载历史对话（MySQL） ----------
            // 仅在 messageList 为空时才加载，避免重复加载已恢复的会话
            if (chatMemory != null && conversationId != null && messageList.isEmpty()) {
                List<Message> history = chatMemory.get(conversationId);
                if (history != null && !history.isEmpty()) {
                    // 过滤掉旧的系统内部提示（如 "You have tools available"）
                    // 这些提示在新一轮对话中会由 nextStepPrompt 重新添加
                    history.removeIf(msg ->
                        msg instanceof UserMessage &&
                        ((UserMessage) msg).getText().contains("You have tools available"));
                    // 在 messageList 头部插入历史（排在现有消息前面）
                    messageList.addAll(0, history);
                }
            }

            // ---------- 阶段 3：记录用户输入 ----------
            messageList.add(new UserMessage(userPrompt));

            // 用于收集 Agent 执行过程中产生的结果
            List<String> results = new ArrayList<>();

            // 累积文件输出（延迟到循环结束后统一发送，避免碎片化输出）
            StringBuilder pendingFileOutput = new StringBuilder();

            // 已展示过文件输出的去重集合（同一个文件链接不重复展示）
            Set<String> seenFileOutputs = new HashSet<>();

            // ---------- 阶段 4：Step 执行循环 ----------
            try {
                // 循环条件：步数未超限 且 状态仍为 RUNNING（未被外部 stop 或内部异常改变）
                for (int i = 0; i < maxSteps && this.state == AgentState.RUNNING; i++) {
                    int stepNumber = i + 1;
                    boolean toolsCalled;
                    String stepResult;

                    // ---- 子阶段 4a：思考（流式） ----
                    // 判断当前 Agent 是否是 ToolCallAgent（支持流式思考）
                    if (this instanceof ToolCallAgent tca) {
                        // 使用 streamThink：实时推送 LLM 输出文本
                        toolsCalled = tca.streamThink(emitter);
                        if (!toolsCalled) {
                            // LLM 没有要求调用工具 → 说明已给出最终答案
                            // 最终答案已在 streamThink 中通过 SSE 流式推送完毕
                            pendingFileOutput.setLength(0);  // 清空待输出文件（避免重复）
                            this.state = AgentState.FINISHED;
                            break;  // 退出循环
                        }
                        // 有工具需要调用 → 执行 act
                        stepResult = tca.act();
                    } else {
                        // 非 ToolCallAgent → 使用普通的 step() 方法（非流式）
                        stepResult = step();
                        toolsCalled = isToolsCalled();
                    }
                    this.currentStep = stepNumber;

                    // ---- 子阶段 4b：提取和推送思考内容 ----
                    // extractLastThought() 从 messageList 中提取 LLM 的最新推理过程
                    // 注意：最终回答文本已在 streamThink 中流式推送（文本 token），
                    // 这里只推送「思考/推理过程」(reasoningContent)，用 💭 符号标记
                    String thought = extractLastThought();
                    if (thought != null) {
                        thought = thought.replace("\\n", "\n").replace("\\t", "\t");
                    }

                    // 累积文件输出（PDF、图片等产生的文件链接）
                    String fileOutput = extractFileOutput(stepResult, seenFileOutputs);
                    if (fileOutput != null) {
                        if (pendingFileOutput.length() > 0) pendingFileOutput.append("\n");
                        pendingFileOutput.append(fileOutput);
                    }

                    // 跳过没有思考内容的工具执行步骤（中间步骤对用户无意义，不展示）
                    if (thought == null || thought.trim().isEmpty()) {
                        continue;
                    }

                    // 清理思考内容并推送给前端（去掉 URL、Windows 路径等）
                    String text = thought != null ? removeUrlLines(thought) : "";
                    if (!text.isEmpty()) {
                        emitter.send("💭 " + text);  // 💭 标记这是思考过程
                    }
                }

                // ---------- 阶段 5：推送累积的文件输出 ----------
                // 循环结束后，将生成的文件链接一次性推送给前端
                if (pendingFileOutput.length() > 0) {
                    // 将图片链接转换为 Markdown 图片格式（让前端可以预览）
                    String files = pendingFileOutput.toString()
                        .replaceAll("/api/files/downloads/[^\\s)\"]+\\.(png|jpg|jpeg|gif|webp)\\b", "![]($0)");
                    emitter.send("\n✨ " + files);  // ✨ 标记这是成果展示
                }

                // 如果是因为达到最大步数而退出，推送提示
                if (currentStep >= maxSteps) {
                    this.state = AgentState.FINISHED;
                    results.add("Agent reached max steps:" + maxSteps);
                    emitter.send("Agent reached max steps:" + maxSteps);
                }

                // 正常完成：通知任务收尾（ADR-3），关闭 SSE 连接
                if (completionCallback != null) {
                    completionCallback.accept(true, null);
                }
                emitter.complete();

            } catch (Exception e) {
                // ---------- 异常处理 ----------
                this.state = AgentState.ERROR;
                log.error("Agent {} encountered an error at step {}: {}", this.name, currentStep + 1, e.getMessage());
                try {
                    emitter.send("Agent encountered an error: " + e.getMessage());
                    if (completionCallback != null) {
                        completionCallback.accept(false, e.getMessage());
                    }
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);  // 连发送错误消息都失败了，直接标记 SSE 错误
                }
            } finally {
                // ---------- 阶段 6：持久化对话历史到 MySQL ----------
                // 无论正常结束还是异常，都尝试保存对话历史
                if (chatMemory != null && conversationId != null && !messageList.isEmpty()) {
                    try {
                        // 先清空旧的历史，再写入新的（全量覆盖策略）
                        chatMemory.clear(conversationId);
                        // 过滤：只保留用户消息和非空的 Assistant 回复
                        // 不保存工具调用相关的内部消息（ToolResponseMessage 等）
                        List<Message> persistentMessages = messageList.stream()
                                .filter(m -> {
                                    if (m instanceof UserMessage) return true;   // 用户输入全部保留
                                    if (m instanceof AssistantMessage) {
                                        return m.getText() != null && !m.getText().isBlank();  // 只保留有内容的回复
                                    }
                                    return false;  // 工具响应等中间消息不持久化
                                })
                                .collect(java.util.stream.Collectors.toList());
                        if (!persistentMessages.isEmpty()) {
                            chatMemory.add(conversationId, persistentMessages);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to persist chat memory: {}", e.getMessage());
                    }
                }
                // 执行清理工作（子类可覆盖此方法释放资源）
                this.cleanup();
            }
        });

        // ---------- SSE 事件回调 ----------

        // 超时回调：Agent 执行超过 10 分钟
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSEEmitter for agent timed out.");
            emitter.complete();
        });

        // 完成回调：SSE 连接正常关闭时
        emitter.onCompletion(() -> {
            log.info("SSEEmitter for agent completed.");
        });

        return emitter;  // 立即返回 SseEmitter，不等待 Agent 执行完成
    }

    /**
     * 执行一步操作（由子类实现）
     *
     * <p>{@link ReActAgent} 实现了此方法，将其分解为 think() + act() 两步。</p>
     *
     * @return 当前步的结果描述字符串
     */
    abstract public String step();

    /**
     * 清理资源（钩子方法，子类可覆盖）
     *
     * <p>在 Agent 执行结束后（正常/异常/超时）被调用。
     * 例如可以在这里释放文件句柄、关闭网络连接等。</p>
     */
    public void cleanup() {};

    /**
     * 外部停止 Agent 执行
     *
     * <p>将状态设置为 FINISHED（模拟正常完成），当前步执行完毕后循环自然退出。
     * 注意：不是硬性中断 LLM 调用（无法中断正在进行中的 HTTP 请求），
     * 而是在下一次循环条件检查时优雅退出。</p>
     *
     * <p>调用场景：用户在前端点击"停止生成"按钮时</p>
     */
    public void stop() {
        this.state = AgentState.FINISHED;
    }

    /**
     * 重置状态，准备下一轮多轮对话
     *
     * <p>重置后 Agent 回到 IDLE 状态，可以接受新的用户输入。
     * 注意：<b>不清空 messageList</b>——保留对话历史是实现多轮对话的关键。
     * 只重置步数和状态。</p>
     *
     * <p>调用时机：前端收到 SSE complete 事件后，准备发送下一条消息前</p>
     */
    public void resetForNextTurn() {
        this.state = AgentState.IDIE;
        this.currentStep = 0;
    }

    // ==================== 私有辅助方法（思考内容处理） ====================

    /**
     * 从 messageList 中提取最新的 LLM 推理内容（reasoningContent）
     *
     * <p><b>提取逻辑（从后往前搜索）：</b></p>
     * <ol>
     *   <li>如果是 DeepSeekAssistantMessage → 优先取 reasoningContent（DeepSeek 的思维链）</li>
     *   <li>如果 reasoningContent 为空 → 降级取 text（普通回复内容）</li>
     *   <li>如果是普通 AssistantMessage → 直接取 text</li>
     * </ol>
     *
     * <p><b>语言过滤：</b>DeepSeek V4 的 CoT（Chain of Thought）默认是英文的，
     * 如果检测到大部分内容是英文（中文占比低于 20%），则返回 null 不展示。
     * 这是为了避免向中文用户展示大段英文推理过程。</p>
     *
     * <p><b>reasoningContent vs text：</b>
     * DeepSeek 等推理模型会分开输出"推理过程"和"最终回答"两部分。
     * reasoningContent 是模型的内心独白（如"用户想要...，我应该...")，
     * text 是最终给用户看的回复内容。</p>
     *
     * @return LLM 的推理/思考内容，如果没有或全是英文则返回 null
     */
    private String extractLastThought() {
        // 从消息列表末尾开始搜索（最近的消息最先被找到）
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message msg = messageList.get(i);
            if (msg instanceof DeepSeekAssistantMessage) {
                // DeepSeek 特有：有 reasoningContent 字段（思维链）
                String r = ((DeepSeekAssistantMessage) msg).getReasoningContent();
                if (r != null && !r.isBlank()) {
                    // 检查是否为英文推理——如果是，不展示（用户看不懂）
                    if (isMostlyEnglish(r)) {
                        return null;
                    }
                    return r;
                }
                return ((AssistantMessage) msg).getText();
            }
            if (msg instanceof AssistantMessage) {
                String text = ((AssistantMessage) msg).getText();
                // 同样过滤英文内容
                if (text != null && isMostlyEnglish(text)) {
                    return null;
                }
                return text;
            }
        }
        return null;
    }

    /**
     * 判断文本是否主要是英文（中文内容占比低于 20%）
     *
     * <p><b>判断逻辑：</b></p>
     * <ol>
     *   <li>忽略空白字符</li>
     *   <li>统计总字符数（排除空白）</li>
     *   <li>统计中文字符数（CJK 统一表意文字范围）</li>
     *   <li>如果中文字符占比低于 20%，则认为是英文为主</li>
     * </ol>
     *
     * <p><b>CJK 字符范围说明：</b></p>
     * <ul>
     *   <li>CJK_UNIFIED_IDEOGRAPHS：基本汉字（U+4E00 - U+9FFF），覆盖常用汉字</li>
     *   <li>CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A：扩展 A 区汉字</li>
     *   <li>CJK_SYMBOLS_AND_PUNCTUATION：中文标点符号（如 、。！？）</li>
     * </ul>
     *
     * @param text 要检查的文本
     * @return true = 主要是英文（不应展示给中文用户），false = 中文内容足够多
     */
    private boolean isMostlyEnglish(String text) {
        if (text == null || text.isBlank()) return false;
        int total = 0, chinese = 0;
        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) continue;  // 跳过空白字符
            total++;
            // 检查是否属于中文 Unicode 区块
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                chinese++;
            }
        }
        // 中文占比低于 20% → 视为"主要英文"
        return total > 0 && (double) chinese / total < 0.2;
    }

    /**
     * 从 messageList 中提取最新的 AssistantMessage 文本内容（getText）
     *
     * <p>与 extractLastThought() 的区别：本方法只取 text，不关心 reasoningContent。</p>
     *
     * @return 最新一条 Assistant 回复的文本内容
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
     * 检查当前步骤是否调用了工具
     *
     * <p><b>判断逻辑：</b>从 messageList 末尾开始检查——</p>
     * <ul>
     *   <li>如果最后一条是 ToolResponseMessage → 工具已经被调用过了 → 返回 true</li>
     *   <li>如果遇到 AssistantMessage（还没执行工具）→ 返回 false</li>
     * </ul>
     *
     * <p>这个方法用于非 ToolCallAgent 的 step() 执行后判断是否调用了工具。</p>
     *
     * @return true = 本次 step 中调用了工具，false = 未调用
     */
    private boolean isToolsCalled() {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            if (messageList.get(i) instanceof ToolResponseMessage) {
                return true;   // 工具已被执行
            }
            // 遇到 AssistantMessage 说明当前轮次的工具还没执行
            if (messageList.get(i) instanceof AssistantMessage) {
                return false;
            }
        }
        return false;
    }

    /**
     * 从 stepResult 字符串中提取可访问的文件 URL
     *
     * <p>工具执行结果中可能包含本地文件路径（如 {@code D:\project\downloads\report.pdf}），
     * 这些路径需要转换为 HTTP URL（如 {@code /api/files/downloads/report.pdf}）才能让前端访问。</p>
     *
     * <p><b>提取逻辑：</b></p>
     * <ol>
     *   <li>按行分割 stepResult</li>
     *   <li>筛选包含 "/api/" 的行（HTTP 可访问路径）</li>
     *   <li>去掉 "toolsXXXresult:" 前缀（工具名和 result 标记）</li>
     *   <li>去掉行首的 📄 符号</li>
     *   <li>通过 seenOutputs 去重（同一文件链接不重复展示）</li>
     * </ol>
     *
     * @param stepResult  act() 返回的工具执行结果字符串
     * @param seenOutputs 已展示过的文件去重集合
     * @return 提取到的文件 URL 文本，如果没有则返回 null
     */
    private String extractFileOutput(String stepResult, Set<String> seenOutputs) {
        if (stepResult == null || stepResult.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        // JSON 序列化时会将 \n 转义为 \\n，先恢复为真正的换行符
        stepResult = stepResult.replace("\\n", "\n");
        for (String line : stepResult.split("\n")) {
            String trimmed = line.trim();
            // 跳过空行和孤立的引号
            if (trimmed.isEmpty() || trimmed.equals("\"")) {
                continue;
            }
            // 只关心包含可访问文件 URL 的行
            if (trimmed.contains("/api/")) {
                // 去掉 "toolsXXXresult:" 前缀（如 "toolsFileOperationToolresult:"）
                int idx = trimmed.indexOf("result:");
                String info = (idx >= 0) ? trimmed.substring(idx + 7).trim() : trimmed;
                // 去掉行首的 📄 标记
                info = info.replaceAll("^📄\\s*", "");
                // 去重：同一个文件路径只展示一次
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
     * 将文本中的本地文件路径转换为可访问的 HTTP URL
     *
     * <p><b>转换示例：</b></p>
     * <pre>{@code
     * "D:\\project\\downloads\\report.pdf" → "/api/files/downloads/report.pdf"
     * }</pre>
     *
     * <p><b>图片链接增强：</b>将图片 URL 自动包裹为 Markdown 图片格式 {@code ![](url)}，
     * 这样前端渲染时可以自动预览图片。</p>
     *
     * @param text 可能包含本地文件路径的文本
     * @return 转换后的文本（本地路径已被 HTTP URL 替换）
     */
    private String removeLocalPaths(String text) {
        if (text == null || text.isEmpty()) return text;
        // 将 Windows 本地路径（盘符:\...\downloads\）替换为 HTTP URL
        String result = text.replaceAll("[a-zA-Z]:[\\\\/].*?downloads[\\\\/]", "/api/files/downloads/");
        // 将未被 Markdown 图片格式包裹的图片 URL 自动加上 ![]()
        result = result.replaceAll(
                "(?<!\\()/api/files/downloads/[^\\s)\"]+\\.(png|jpg|jpeg|gif|webp)\\b",
            "![]($0)"
        );
        return result;
    }

    /**
     * 从思考内容中移除对用户无意义的行
     *
     * <p><b>会移除的内容：</b></p>
     * <ul>
     *   <li>包含 http:// 或 https:// 的 URL 行</li>
     *   <li>工具执行状态行（如 "3 张图片下载成功"、"1 张图片下载失败"）</li>
     *   <li>Windows 本地路径行（如 "C:\Users\..."）</li>
     *   <li>LLM 的自我纠错行（如 "The error was..."、"Let me remove..."）</li>
     * </ul>
     *
     * <p><b>目的：</b>这些内容是 LLM 在处理工具结果时产生的"内部独白"，
     * 对用户没有实际价值，展示出来反而会让用户困惑。</p>
     *
     * @param text LLM 的思考内容原文
     * @return 清理后的文本
     */
    private String removeUrlLines(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;                          // 跳过空行
            if (trimmed.contains("http://") || trimmed.contains("https://")) continue;  // 跳过 URL 行
            if (trimmed.matches(".*\\d+\\s*张.*(?:成功|失败).*")) continue;  // 跳过 "X 张图片下载成功/失败"
            if (trimmed.matches(".*[A-Za-z]:[/\\\\].*")) continue;   // 跳过 Windows 路径
            // 跳过 LLM 的自我纠错语句（对用户无意义）
            if (trimmed.startsWith("The error")) continue;
            if (trimmed.startsWith("Let me remove")) continue;
            if (trimmed.startsWith("I should avoid")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }
}
