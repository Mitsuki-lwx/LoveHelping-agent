package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.harness.governance.JevSelfHarmSignal;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphRunner;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.infrastructure.scheduler.OnlineLoadTracker;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** One admission/lifecycle path for normal, RAG, Agent and sandbox requests (ADR-23). */
@Slf4j
@Component
public class ChatEntry {

    /** L3 自伤转介文案（与 GuardrailAdvisor 同源措辞，含援助热线）。 */
    private static final String REFERRAL_TEXT =
            "我注意到你现在可能非常难受。如果你正在经历难以承受的时刻，请联系专业援助：全国心理援助热线 400-161-9995。你不需要独自面对。";
    /** L3 通用阻断文案。 */
    private static final String BLOCK_TEXT =
            "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
    private final GuardrailRuleService guardrails;
    private final JevSelfHarmSignal jevSignal;
    private final RateLimiter rateLimiter;
    private final CapabilityRouter router;
    private final GraphRunner graphRunner;
    private final StreamRegistry streams;
    private final OnlineLoadTracker online;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final MemoryService memory;
    private final boolean brakeEnabled;
    private final int brakeStart, brakeEnd;
    private final long timeoutMs;

    public ChatEntry(GuardrailRuleService guardrails, JevSelfHarmSignal jevSignal, RateLimiter rateLimiter, CapabilityRouter router,
                     GraphRunner graphRunner, StreamRegistry streams, OnlineLoadTracker online,
                     MeterRegistry meters, Tracer tracer, MemoryService memory,
                     @Value("${app.emotion-brake.enabled:true}") boolean brakeEnabled,
                     @Value("${app.emotion-brake.start-hour:23}") int brakeStart,
                     @Value("${app.emotion-brake.end-hour:6}") int brakeEnd,
                     @Value("${app.chat.timeout-ms:90000}") long timeoutMs) {
        this.guardrails = guardrails; this.jevSignal = jevSignal;
        this.rateLimiter = rateLimiter; this.router = router;
        this.graphRunner = graphRunner; this.streams = streams; this.online = online;
        this.meters = meters; this.tracer = tracer; this.memory = memory;
        this.brakeEnabled = brakeEnabled; this.brakeStart = brakeStart; this.brakeEnd = brakeEnd;
        this.timeoutMs = Math.max(1, timeoutMs);
    }

    public AgentResult chat(String message, String chatId, List<Long> mediaIds, boolean forceAgent, BiConsumer<Boolean, String> callback) {
        return chat(message, chatId, mediaIds, forceAgent, false, callback);
    }

    public AgentResult chat(String message, String chatId, List<Long> mediaIds, boolean forceAgent,
                            boolean continueBrake, BiConsumer<Boolean, String> callback) {
        return chat(message, chatId, mediaIds, forceAgent, continueBrake, callback, forceAgent ? "agent" : "love");
    }

    /** 会话归属类型由调用入口语义决定（love/agent），不跟着内部编排路由走。 */
    public AgentResult chat(String message, String chatId, List<Long> mediaIds, boolean forceAgent,
                            boolean continueBrake, BiConsumer<Boolean, String> callback, String conversationType) {
        validate(message, chatId);
        guardrailCheck(message, continueBrake);
        String user = Optional.ofNullable(TenantContext.getUserId()).orElse("anonymous");
        // Public anonymous chat is stateless; never use a supplied ID to read a registered user's history.
        String key = "anonymous".equals(user) ? "anon-" + UUID.randomUUID() : chatId;
        Map<String, Object> input = baseInput(message, key, user);
        input.put(GraphStateKeys.ADVICE, router.isAdviceRequest(message));
        if (mediaIds != null && !mediaIds.isEmpty()) {
            if (mediaIds.size() > 4 || mediaIds.stream().anyMatch(id -> id == null || id <= 0)) throw new BizException(400, "图片数量或编号无效");
            input.put(GraphStateKeys.MEDIA_IDS, List.copyOf(mediaIds));
        }
        if (forceAgent) input.put(GraphStateKeys.FORCE_AGENT, true);
        return new AgentResult.ShallowResult(execute(input, key, callback, () -> {
            if (!"anonymous".equals(user)) memory.claimConversation(user, chatId, conversationType);
        }));
    }

    /** Caller verifies sandbox ownership before invoking; all capacity/rate/timeout protections are shared. */
    public Flux<String> sandbox(String message, Long sandboxId, String user) {
        String key = String.valueOf(sandboxId); // Preserve existing sandbox memory/checkpoint IDs.
        validate(message, String.valueOf(sandboxId));
        guardrailCheck(message, true);
        Map<String, Object> input = baseInput(message, key, user);
        input.put(GraphStateKeys.SANDBOX_ID, sandboxId);
        input.put(GraphStateKeys.ADVICE, false);
        return execute(input, key, null, () -> {});
    }

    private Map<String, Object> baseInput(String message, String key, String user) {
        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.MESSAGE, message); input.put(GraphStateKeys.CHAT_ID, key); input.put(GraphStateKeys.USER_ID, user);
        var parent = tracer.currentSpan();
        if (parent != null) {
            input.put(GraphStateKeys.PIPELINE_TRACE_ID, parent.context().traceId());
            input.put(GraphStateKeys.PIPELINE_SPAN_ID, parent.context().spanId());
            input.put(GraphStateKeys.PIPELINE_SAMPLED, Boolean.TRUE.equals(parent.context().sampled()));
        }
        return input;
    }

    private Flux<String> execute(Map<String, Object> input, String key, BiConsumer<Boolean, String> callback, Runnable authorize) {
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) return Flux.error(new BizException(409, "同一请求不能重复订阅"));
            AtomicBoolean finished = new AtomicBoolean();
            BiConsumer<Boolean, String> notify = (ok, reason) -> {
                if (finished.compareAndSet(false, true) && callback != null) {
                    try { callback.accept(ok, reason); } catch (RuntimeException ignored) { metric("task_callback_error"); }
                }
            };
            OnlineLoadTracker.Admission admission = online.admit();
            if (!admission.admitted()) { notify.accept(false, "overloaded"); return Flux.error(overloaded(admission)); }
            long start = System.nanoTime();
            AtomicReference<StreamRegistry.StreamSink> registered = new AtomicReference<>();
            AtomicReference<CompletableFuture<Map<String, Object>>> future = new AtomicReference<>();
            return Flux.<String>create(sink -> {
                try {
                    authorize.run();
                    rateLimiter.acquire(Objects.toString(input.get(GraphStateKeys.USER_ID), "anonymous"));
                    if (sink.isCancelled()) return;
                    StreamRegistry.StreamSink stream = streams.register(key, sink);
                    registered.set(stream);
                    if (sink.isCancelled()) { streams.unregister(key, stream); return; }
                    CompletableFuture<Map<String, Object>> run = graphRunner.runAsync(input, key);
                    future.set(run);
                    sink.onCancel(() -> graphRunner.stop(key, run));
                    if (sink.isCancelled()) { graphRunner.stop(key, run); return; }
                    run.whenComplete((result, error) -> {
                        if (sink.isCancelled()) return;
                        if (error != null) { sink.error(unwrap(error)); return; }
                        if (!stream.toolsStreamed()) {
                            Object tools = result.get(GraphStateKeys.TOOL_EVENTS);
                            if (tools instanceof List<?> list) for (Object tool : list) sink.next("调用工具: " + tool);
                        }
                        if (!stream.streamed()) for (String part : chunk(Objects.toString(result.get(GraphStateKeys.OUTPUT), ""))) sink.next(part);
                        Object advice = result.get(GraphStateKeys.ADVICE_TIERS);
                        if (advice != null && !advice.toString().isBlank()) sink.next(ChatExecutor.ADVICE_EVENT_MARKER + advice);
                        sink.complete();
                    });
                } catch (RuntimeException error) { sink.error(error); }
            }, reactor.core.publisher.FluxSink.OverflowStrategy.ERROR)
            // MVC requests one event at a time. Absorb short bursts, but never use an unbounded sink.
            .onBackpressureBuffer(256, ignored -> metric("backpressure_rejected"), reactor.core.publisher.BufferOverflowStrategy.ERROR)
            // Real total deadline, not an idle timeout reset by every text chunk.
            .takeUntilOther(reactor.core.publisher.Mono.delay(Duration.ofMillis(timeoutMs))
                    .flatMap(t -> reactor.core.publisher.Mono.error(new java.util.concurrent.TimeoutException("Chat deadline exceeded"))))
            .doOnComplete(() -> notify.accept(true, null))
            .doOnError(e -> notify.accept(false, e instanceof java.util.concurrent.TimeoutException ? "timeout" : "execution_failed"))
            .doFinally(signal -> {
                try {
                    CompletableFuture<?> run = future.get();
                    if (signal != SignalType.ON_COMPLETE && run != null) graphRunner.stop(key, run);
                    streams.unregister(key, registered.get());
                    if (signal == SignalType.CANCEL) notify.accept(false, "cancelled");
                    metric(signal.name().toLowerCase(Locale.ROOT));
                } finally {
                    online.recordDuration((System.nanoTime() - start) / 1_000_000L);
                    online.exit();
                }
            });
        });
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) return error.getCause();
        return error;
    }
    private BizException overloaded(OnlineLoadTracker.Admission admission) {
        int retry = Math.min(30, Math.max(2, (int) Math.ceil(online.avgDurationMs() / 1000.0)));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("currentLoad", online.inFlight());
        data.put("maxLoad", online.maxInFlight());
        data.put("queueDepth", online.queueDepth());
        data.put("retryAfterSec", retry);
        data.put("reason", admission.reason());
        data.put("waitedMs", admission.waitedMs());
        // 排队等过仍没排到 → 告诉用户"等过了"；从未排上队（队列已满）→ 只给重试建议。
        String message = "wait_timeout".equals(admission.reason())
                ? String.format("当前咨询较多，已等待 %.1f 秒仍未排到，请稍后再试", admission.waitedMs() / 1000.0)
                : "当前咨询较多，请稍后再试";
        return new BizException(4003, message, data);
    }
    private void validate(String message, String id) {
        if (message == null || message.isBlank() || message.length() > 8000) throw new BizException(400, "消息不能为空且不能超过 8000 字符");
        if (id == null || id.isBlank() || id.length() > 100 || !id.matches("[A-Za-z0-9_-]+")) throw new BizException(400, "会话编号格式无效");
    }
    private void guardrailCheck(String prompt, boolean continueBrake) {
        if (isPromptLeakProbe(prompt)) {
            metric("prompt_leak_blocked");
            throw new BizException(4001, "这些是我的内部设定，不方便透露。有什么情感或关系上的问题，我很乐意帮你聊聊。");
        }
        var verdict = guardrails.check(prompt);
        if (verdict.level() > 0) meters.counter("guardrail.trigger", "level", String.valueOf(verdict.level()), "rule_id", verdict.ruleId()).increment();
        if (verdict.level() >= 3) {
            metric("l3_blocked");
            throw new BizException(4001, "self_harm".equals(verdict.ruleId()) ? REFERRAL_TEXT : BLOCK_TEXT);
        }
        // Jev 第二信号（2026-09-20，docs/phase7-guardrail-recall）：**只加召回，不替兜底**。
        // 位置在词典 L3 分支**之后**——词典已判 L3 的消息根本走不到这里，Jev 无法放行它；
        // 反过来说，词典命中过的消息也不会多付这一次网络往返（实测拦截响应仍是 10~50ms）。
        // 放在 ChatEntry 而不是 GuardrailAdvisor：这里拿到的是**用户原话**，
        // 而 advisor 那层的 user message 已掺入技能/检索注入，同一句话的概率会被稀释。
        if (jevSignal.flagged(prompt)) {
            metric("l3_blocked");
            meters.counter("guardrail.trigger", "level", "3", "rule_id", JevSelfHarmSignal.RULE_ID).increment();
            log.warn("Guardrail L3 blocked by Jev second signal: {}", prompt.length() > 40 ? prompt.substring(0, 40) + "…" : prompt);
            throw new BizException(4001, REFERRAL_TEXT);
        }
        if (brakeEnabled && !continueBrake && verdict.level() >= 2 && isLateNight() && guardrails.matchesEmotionBrake(prompt))
            throw new BizException(4002, "我注意到你现在情绪比较激动。可以先冷静一下再继续；确认仍要发送时请携带 continueBrake=true。");
    }
    private boolean isPromptLeakProbe(String prompt) {
        if (prompt.length() > 200) return false;
        String p = prompt.toLowerCase(Locale.ROOT);
        if (p.contains("system prompt") || p.contains("systemprompt") || p.contains("系统提示")) return true;
        return List.of("翻译", "打印", "复述", "总结", "输出", "显示", "告诉", "说明", "列出").stream().anyMatch(p::contains)
                && List.of("规则", "指令", "设定", "提示词", "开发者").stream().anyMatch(p::contains);
    }
    private boolean isLateNight() {
        int hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).getHour();
        return brakeStart <= brakeEnd ? hour >= brakeStart && hour < brakeEnd : hour >= brakeStart || hour < brakeEnd;
    }
    private void metric(String outcome) { try { meters.counter("chat.request", "mode", "graph", "status", outcome).increment(); } catch (RuntimeException ignored) {} }
    private List<String> chunk(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += 30) parts.add(text.substring(i, Math.min(text.length(), i + 30)));
        return parts;
    }
}
