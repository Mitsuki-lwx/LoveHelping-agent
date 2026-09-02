package cn.lwx.lwxaiagent.infrastructure.orchestration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 真流式桥（2026-09-02）：把图内 LLM 的流式增量实时转发到 SSE sink。
 *
 * <p><b>动机</b>：原实现是图内节点 {@code collectList().block()} 把 LLM 流收完、
 * 图完成后 ChatEntry 再 chunk() 模拟打字机——TTFT ≈ 总延迟（实测单条 3.4-38s 全等完）。
 * 本组件让节点在生成时逐块推送给已订阅的 SSE sink，实现真流式。</p>
 *
 * <p><b>为什么用注册表而非图 state 传引用</b>：OrchestrationGraph 挂 RedisSaver，
 * state 会被序列化到 Redis checkpoint——sink 引用放 state 会序列化失败（8/31 教训：
 * trace 上下文只能用字符串透传）。故以 chatId 为 key 注册/查询，state 中只需既有字符串。</p>
 *
 * <p><b>出站事件类型（输出层白名单，2026-09-02 输出层预留）</b>——用户可见流只允许以下事件：
 * <ul>
 *   <li>TEXT（正文，默认流式）</li>
 *   <li>TOOL（🔧 工具事件，ChatEntry 发出）</li>
 *   <li>ADVICE（@@ADVICE@@ 结构卡片，协议场景）</li>
 *   <li>ERROR（错误兜底文案）</li>
 *   <li>REASONING（思考过程，{@code app.output.reasoning-mode} 决定；默认 discard）</li>
 * </ul>
 * 任何其他内容（模型原始混合输出、内部标记、prompt 片段）不得直接出站——
 * 必须先经节点/ChatEntry 归类。思考模型（deepseek-r1/glm thinking 等）接入时，
 * reasoning 内容应经 {@link StreamSink#appendReasoning} 交给本层裁决，禁止拼入正文流。</p>
 *
 * <p>线程安全：{@link FluxSink#next} 本身线程安全，本类再以 synchronized 保护
 * pending/标志位状态；sink 被下游取消（客户端断连）后置 cancelled，静默停止推送。</p>
 */
@Slf4j
@Component
public class StreamRegistry {

    /** advice 事件标记（与 ChatExecutor.ADVICE_EVENT_MARKER 保持同一事实源） */
    private static final String MARKER = ChatExecutor.ADVICE_EVENT_MARKER;
    /** marker 跨块安全窗口：pending 保留此长度的尾巴再发射，防 marker 被截断漏检 */
    private static final int WINDOW = MARKER.length() + 16;
    /** reasoning 出站前缀（stream 模式）：与正文文本区分，前端可按 §R§ 折叠展示 */
    private static final String REASONING_PREFIX = "§R§";

    private final ConcurrentHashMap<String, StreamSink> sinks = new ConcurrentHashMap<>();
    /** 思考过程出站策略（app.output.reasoning-mode，默认 discard：思考不糊用户脸） */
    private final String reasoningMode;

    public StreamRegistry(@Value("${app.output.reasoning-mode:discard}") String reasoningMode) {
        this.reasoningMode = reasoningMode == null ? "discard" : reasoningMode;
    }

    /** 注册（订阅建立时调用；重复注册以新 sink 覆盖，旧 sink 置 cancelled 防悬挂推送） */
    public StreamSink register(String chatId, FluxSink<String> sink) {
        StreamSink old = sinks.put(chatId, new StreamSink(sink, reasoningMode));
        if (old != null) {
            old.cancel();
        }
        return sinks.get(chatId);
    }

    public void unregister(String chatId) {
        sinks.remove(chatId);
    }

    /** 查询；无则 null（调用方决定是否兜底） */
    public StreamSink get(String chatId) {
        return sinks.get(chatId);
    }

    /**
     * 请求级流式 sink 包装。用法：
     * <pre>
     *   flux.doOnNext(sink::append).collectList().block();  // append 在 LLM 线程实时转发
     *   sink.flush();                                        // 流结束后收尾发射
     * </pre>
     */
    public static final class StreamSink {
        private final FluxSink<String> sink;
        private final String reasoningMode;
        private final StringBuilder pending = new StringBuilder();
        /** advice marker 已出现（其后内容为结构化 JSON，不再走文本流） */
        private boolean adviceSeen;
        /** 是否剥离 advice marker——仅 advice 协议请求开启（2026-09-02 dirty_1：
         *  普通对话被诱导写 @@ADVICE@@ 字面量时误剥成空回复，故默认不剥） */
        private volatile boolean stripMarker;
        /** 文本是否已通过真流式转发（ChatEntry 判断是否还需 chunk 兜底） */
        private volatile boolean streamed;
        private volatile boolean cancelled;

        StreamSink(FluxSink<String> sink, String reasoningMode) {
            this.sink = sink;
            this.reasoningMode = reasoningMode;
        }

        /** 开启 advice marker 剥离（仅话术三级协议请求调用，须在首次 append 前） */
        public synchronized void enableMarkerStripping() {
            this.stripMarker = true;
        }

        /**
         * 思考过程（reasoning）出站入口（输出层，思考模型接入时调用）。
         * <p>策略：discard（默认）= 不发给用户（打 debug 日志，思考不糊脸）；
         * stream = 以 {@code §R§} 前缀独立行发出（前端可按前缀折叠为"思考过程"）。</p>
         */
        public synchronized void appendReasoning(String reasoning) {
            if (cancelled || reasoning == null || reasoning.isBlank()) {
                return;
            }
            if ("stream".equalsIgnoreCase(reasoningMode)) {
                emit(REASONING_PREFIX + reasoning);
            } else {
                log.debug("Reasoning discarded by output layer (reasoning-mode=discard): {}",
                        reasoning.length() > 80 ? reasoning.substring(0, 80) + "..." : reasoning);
            }
        }

        /** 追加一段 LLM 增量：剥离 advice marker 后实时转发文本部分 */
        public synchronized void append(String text) {
            if (cancelled || text == null || text.isEmpty()) {
                return;
            }
            // 非协议模式（advice=false）：模型输出即正文，无需 marker 窗口——
            // 直接转发，避免用户讨论 @@ADVICE@@ 字面量被误剥成空回复
            if (!stripMarker) {
                emit(text);
                return;
            }
            pending.append(text);
            int idx;
            while ((idx = pending.indexOf(MARKER)) >= 0) {
                // marker 前的文本是给用户的回复 → 发射
                if (idx > 0) {
                    emit(pending.substring(0, idx));
                }
                // marker 及其后为结构化 advice payload，不进文本流
                pending.setLength(0);
                adviceSeen = true;
            }
            if (!adviceSeen && pending.length() > WINDOW) {
                emit(pending.substring(0, pending.length() - WINDOW));
                pending.delete(0, pending.length() - WINDOW);
            }
        }

        /** 流结束收尾：发射剩余文本（advice 路径的尾巴是 payload，丢弃） */
        public synchronized void flush() {
            if (cancelled) {
                return;
            }
            if (stripMarker && adviceSeen) {
                pending.setLength(0); // marker 后残留 payload 不发射
            } else if (pending.length() > 0) {
                emit(pending.toString());
                pending.setLength(0);
            }
            if (!cancelled) {
                streamed = true; // 推送失败（sink 已断）时不置位，调用方兜底处理
            }
        }

        public boolean streamed() {
            return streamed;
        }

        public void cancel() {
            cancelled = true;
        }

        private void emit(String s) {
            if (cancelled || s.isEmpty()) {
                return;
            }
            try {
                sink.next(s);
            } catch (Exception e) {
                // 下游已取消（客户端断连）等：静默停止，避免把图执行拖挂
                cancelled = true;
                log.debug("Stream sink cancelled, stop pushing: {}", e.getMessage());
            }
        }
    }
}
