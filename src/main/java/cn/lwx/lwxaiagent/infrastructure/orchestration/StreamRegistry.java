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
    /**
     * 输出侧护栏判定（ADR-55）。null = 不检查（单测 / 无护栏场景），行为等于改造前。
     *
     * <p>用<b>函数接口</b>而不是直接依赖 {@code GuardrailRuleService}：一是让本类不必绑定
     * 具体判定实现（单测可传 null 或桩），二是避免 {@code orchestration} 包对
     * {@code harness.governance} 的强耦合。</p>
     */
    private final OutputGuardrail outputGuardrail;

    /** 出站护栏判定：返回命中的 rule_id；{@code null} = 未命中，应正常送达 */
    @FunctionalInterface
    public interface OutputGuardrail {
        String hit(String text);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StreamRegistry(@Value("${app.output.reasoning-mode:discard}") String reasoningMode,
                          cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService guardrailRules) {
        this.reasoningMode = reasoningMode == null ? "discard" : reasoningMode;
        this.outputGuardrail = guardrailRules == null ? null : text -> {
            var v = guardrailRules.check(text,
                    cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService.Scope.OUTPUT);
            return v.level() >= 3 ? v.ruleId() : null;
        };
    }

    /** 兼容构造器（单测用）：不做出站护栏检查 —— 单测零改动，行为与 ADR-55 之前一致 */
    public StreamRegistry(String reasoningMode) {
        this(reasoningMode, null);
    }

    /** Subscription-scoped registration. Duplicate sessions fail without replacing the original sink. */
    public StreamSink register(String chatId, FluxSink<String> sink) {
        StreamSink fresh = new StreamSink(sink, reasoningMode, outputGuardrail);
        if (sinks.putIfAbsent(chatId, fresh) != null) {
            throw new cn.lwx.lwxaiagent.common.BizException(409, "当前会话仍有请求处理中，请等待完成或先停止");
        }
        return fresh;
    }

    public void unregister(String chatId, StreamSink expected) {
        if (expected != null && sinks.remove(chatId, expected)) expected.cancel();
    }

    public int activeCount() { return sinks.size(); }

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
        /** 工具事件（🔧）是否已由节点实时发出（AgentToolNode 置位，防 ChatEntry 重复发） */
        private volatile boolean toolsStreamed;
        private volatile boolean cancelled;
        private String pendingHighSurrogate = "";
        /** 输出侧护栏判定（ADR-55）；null = 不检查（单测/兼容构造） */
        private final StreamRegistry.OutputGuardrail outputGuardrail;
        /**
         * 护栏尾部窗口：关键词可能**跨 chunk**到达（"伤害" + "自己"），
         * 所以累积最近 {@link #GUARDRAIL_WINDOW} 个字符再判定，而不是只看当次 chunk。
         */
        private final StringBuilder guardrailWindow = new StringBuilder();
        private static final int GUARDRAIL_WINDOW = 64;
        /** 已因 L3 拦截：后续所有 chunk 丢弃（替换文案已在拦截时推过一次） */
        private volatile boolean guardrailBlocked;

        StreamSink(FluxSink<String> sink, String reasoningMode) {
            this(sink, reasoningMode, null);
        }

        StreamSink(FluxSink<String> sink, String reasoningMode, StreamRegistry.OutputGuardrail outputGuardrail) {
            this.sink = sink;
            this.reasoningMode = reasoningMode;
            this.outputGuardrail = outputGuardrail;
        }

        /** 出站是否已被护栏拦截（{@code ChatEntry} 据此避免重复推送替换文案） */
        public boolean guardrailBlocked() {
            return guardrailBlocked;
        }

        /** 开启 advice marker 剥离（仅话术三级协议请求调用，须在首次 append 前） */
        public synchronized void enableMarkerStripping() {
            this.stripMarker = true;
        }

        /** 标记工具事件（🔧）已由节点实时发出——ChatEntry 据此跳过兜底 TOOL_EVENTS 转发 */
        public synchronized void markToolsStreamed() {
            this.toolsStreamed = true;
        }

        public boolean toolsStreamed() {
            return toolsStreamed;
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
            if (adviceSeen) return; // Do not accumulate an unbounded structured payload after its marker.
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

        /**
         * 出站护栏检查点（ADR-55 / D1）。
         *
         * <p><b>为什么必须在这里做</b>：本方法是**所有出站文本的唯一出口**
         * （{@code append} / {@code appendReasoning} / {@code flush} 都走它），
         * 所以在这里加一道检查点，四条流式入口（Normal / QuickAnswer / AgentTool / OffTopic）
         * <b>一次性全被覆盖</b> —— 不必在每个节点各加一遍（那是漏改的温床）。</p>
         *
         * <p>⛔ <b>顺序是"先判后发"</b>：命中则<b>这段文本不推送</b>，直接改推替换文案。
         * 原来的缺陷正是"先发后判"（图末端 {@code CheckNode} 事后替换，而正文早已流出）。</p>
         *
         * <p>⚠️ <b>判定异常时放行</b>（fail-open）：护栏是本地规则匹配，异常概率极低；
         * 若因护栏故障就阻断对话，等于把一个安全增强变成可用性事故。
         * 异常会打 WARN，且图末端的 {@code CheckNode} 仍有一道事后兜底。</p>
         */
        private void emit(String s) {
            if (cancelled || sink.isCancelled() || s.isEmpty()) {
                return;
            }
            if (guardrailBlocked) {
                return; // 已拦：后续 chunk 全部丢弃（不能只拦命中的那一块）
            }
            if (outputGuardrail != null) {
                String hitRule = guardrailHitRule(s);
                if (hitRule != null) {
                    guardrailBlocked = true;
                    log.warn("流式出站护栏 L3 拦截（ADR-55, rule={}）：已丢弃命中文本，改推替换文案（前 40 字：{}）",
                            hitRule, s.length() > 40 ? s.substring(0, 40) + "…" : s);
                    // 文案按命中的规则选（自伤类 / 其它）—— 不能一律推自伤转介
                    doEmit(cn.lwx.lwxaiagent.harness.governance.GuardrailMessages.forRule(hitRule));
                    return;
                }
            }
            doEmit(s);
        }

        /** 累积尾部窗口后判定（关键词跨 chunk 也能命中）；返回命中的 rule_id 或 null。护栏故障一律放行 */
        private String guardrailHitRule(String s) {
            guardrailWindow.append(s);
            if (guardrailWindow.length() > GUARDRAIL_WINDOW) {
                guardrailWindow.delete(0, guardrailWindow.length() - GUARDRAIL_WINDOW);
            }
            try {
                return outputGuardrail.hit(guardrailWindow.toString());
            } catch (RuntimeException e) {
                log.warn("出站护栏判定异常，放行正文（不因护栏故障阻断对话）：{}", e.toString());
                return null;
            }
        }

        /** 实际的出站动作（不含护栏检查，避免拦截时递归） */
        private void doEmit(String s) {
            if (cancelled || sink.isCancelled() || s.isEmpty()) {
                return;
            }
            s = pendingHighSurrogate + s;
            pendingHighSurrogate = "";
            if (Character.isHighSurrogate(s.charAt(s.length() - 1))) {
                pendingHighSurrogate = s.substring(s.length() - 1);
                s = s.substring(0, s.length() - 1);
            }
            if (s.isEmpty()) return;
            if (s.length() > 8192) {
                cancelled = true;
                sink.error(new cn.lwx.lwxaiagent.common.BizException(5000, "响应块超过安全上限，请稍后再试"));
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
