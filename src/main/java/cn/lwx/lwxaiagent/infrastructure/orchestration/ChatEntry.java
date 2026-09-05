package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphRunner;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天统一入口（ADR-19 收编后）：入口交叉关注点（护栏/限流）+ 业务编排图执行。
 * <p>不再自行路由分发——路由由 {@code OrchestrationGraph.classify} 承担；
 * 本类把图异步执行结果桥接为 SSE Flux（文本分块模拟流式 + 🔧 工具可视化 + advice 事件标记）。</p>
 */
@Slf4j
@Component
public class ChatEntry {

    private final GuardrailRuleService guardrailRuleService;
    private final RateLimiter rateLimiter;
    private final CapabilityRouter router;
    private final GraphRunner graphRunner;
    private final StreamRegistry streamRegistry;
    /** 在线负载/并发闸门（ADR-20 补强 + OWASP LLM10，2026-09-03） */
    private final cn.lwx.lwxaiagent.infrastructure.scheduler.OnlineLoadTracker onlineLoad;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final io.micrometer.tracing.Tracer tracer;

    /** 情绪刹车片配置（FR-CORE-02） */
    private final boolean emotionBrakeEnabled;
    private final int emotionBrakeStartHour;
    private final int emotionBrakeEndHour;

    public ChatEntry(GuardrailRuleService guardrailRuleService,
                     RateLimiter rateLimiter,
                     CapabilityRouter router,
                     GraphRunner graphRunner,
                     StreamRegistry streamRegistry,
                     cn.lwx.lwxaiagent.infrastructure.scheduler.OnlineLoadTracker onlineLoad,
                     io.micrometer.core.instrument.MeterRegistry meterRegistry,
                     io.micrometer.tracing.Tracer tracer,
                     @Value("${app.emotion-brake.enabled:true}") boolean emotionBrakeEnabled,
                     @Value("${app.emotion-brake.start-hour:23}") int emotionBrakeStartHour,
                     @Value("${app.emotion-brake.end-hour:6}") int emotionBrakeEndHour) {
        this.guardrailRuleService = guardrailRuleService;
        this.rateLimiter = rateLimiter;
        this.router = router;
        this.graphRunner = graphRunner;
        this.streamRegistry = streamRegistry;
        this.onlineLoad = onlineLoad;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.emotionBrakeEnabled = emotionBrakeEnabled;
        this.emotionBrakeStartHour = emotionBrakeStartHour;
        this.emotionBrakeEndHour = emotionBrakeEndHour;
    }

    public AgentResult chat(String message, String chatId, List<Long> mediaIds,
                            boolean forceAgent, java.util.function.BiConsumer<Boolean, String> taskCallback) {
        return chat(message, chatId, mediaIds, forceAgent, false, taskCallback);
    }

    /**
     * 统一入口（ADR-19）：护栏/限流 → 业务编排图。
     * @param continueBrake 用户已在冷静提示后明确选择"继续发送"（FR-CORE-02 出口）：
     *                      跳过情绪刹车片（仍保留 L3 硬阻断）
     */
    public AgentResult chat(String message, String chatId, List<Long> mediaIds,
                            boolean forceAgent, boolean continueBrake,
                            java.util.function.BiConsumer<Boolean, String> taskCallback) {
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";

        // ① 入口交叉关注点（保留）
        guardrailCheck(message, continueBrake);
        rateLimiter.checkQuota(userId);
        // 全局并发闸门（OWASP LLM10）：在线请求同时超过 app.online.max-inflight 时
        // 直接给用户友好提示，避免 LLM 被打满（可用性 + 成本双重失控）
        if (onlineLoad != null && !onlineLoad.enter()) {
            throw new BizException(4003, "当前咨询比较多，稍等一下再问我会更好。");
        }

        // ② 话术三级判定
        boolean advice = router.isAdviceRequest(message);

        // ③ 指标
        recordChatRequest("graph");

        // ④ 组装图输入
        Map<String, Object> input = new HashMap<>();
        input.put(GraphStateKeys.MESSAGE, message);
        input.put(GraphStateKeys.CHAT_ID, chatId);
        input.put(GraphStateKeys.USER_ID, userId);
        input.put(GraphStateKeys.ADVICE, advice);
        if (mediaIds != null && !mediaIds.isEmpty()) {
            input.put(GraphStateKeys.MEDIA_IDS, mediaIds); // 视觉节点（ADR-11）
        }
        if (forceAgent) {
            input.put(GraphStateKeys.FORCE_AGENT, true); // LoveManus 通道
        }

        // ④b 全链路 trace 串联：把 HTTP 入口 span 上下文透传给异步图执行
        // （SSE 异步线程丢失请求 trace 上下文，导致 LLM/embedding 变成孤立 root trace）
        var entrySpan = tracer.currentSpan();
        if (entrySpan != null) {
            input.put(GraphStateKeys.PIPELINE_TRACE_ID, entrySpan.context().traceId());
            input.put(GraphStateKeys.PIPELINE_SPAN_ID, entrySpan.context().spanId());
        }

        // ⑤ 异步执行图 → SSE Flux（文本分块 + 🔧 可视化 + advice 事件）+ 任务完成回调
        java.util.function.BiConsumer<Boolean, String> cb =
                taskCallback != null ? taskCallback : (ok, err) -> {};
        Flux<String> flux = Flux.create(sink -> {
            // 真流式桥（2026-09-02）：注册本请求的 SSE sink，图内 normal/simple 节点
            // 在 LLM 生成时实时推送文本增量（advice marker 由 sink 剥离）
            String regKey = chatId == null ? "anon" : chatId;
            StreamRegistry.StreamSink streamSink = streamRegistry.register(regKey, sink);
            graphRunner.runAsync(input, regKey)
                    // 中危修复（2026-09-05）：图执行超时保护——90s 未完成则结束 SSE
                    // （模型悬挂时此前无限等待：SSE 永不结束 + 在线闸门计数泄漏）
                    .orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .thenAccept(result -> {
                        @SuppressWarnings("unchecked")
                        List<String> tools = (List<String>) result.getOrDefault(GraphStateKeys.TOOL_EVENTS, List.of());
                        // 真流式（ADR-21）：agent 工具执行时已实时发 🔧（AgentToolNode 置位）——
                        // 只有非流式/兜底路径才在此补发，避免重复
                        boolean toolsAlreadySent = streamSink != null && streamSink.toolsStreamed();
                        if (!toolsAlreadySent) {
                            for (String t : tools) {
                                sink.next("🔧 调用工具: " + t);
                            }
                        }
                        String output = String.valueOf(result.getOrDefault(GraphStateKeys.OUTPUT, ""));
                        // agent/视觉路径未接真流式（节点不用 registry）→ 兜底 chunk 保持行为
                        if (streamSink == null || !streamSink.streamed()) {
                            for (String part : chunk(output)) {
                                sink.next(part);
                            }
                        }
                        Object tiers = result.get(GraphStateKeys.ADVICE_TIERS);
                        if (tiers != null && !tiers.toString().isBlank()) {
                            sink.next(ChatExecutor.ADVICE_EVENT_MARKER + tiers);
                        }
                        sink.complete();
                        streamRegistry.unregister(regKey);
                        cb.accept(true, null); // agent_task 完成回调（LoveManus 通道）
                    })
                    .exceptionally(err -> {
                        log.error("ChatEntry graph run failed ({}): {}", chatId, err.getMessage());
                        sink.next("系统繁忙，请稍后再试。");
                        sink.complete();
                        streamRegistry.unregister(regKey);
                        cb.accept(false, err.getMessage()); // 失败回调 → 任务 FAILED
                        return null;
                    });
        });

        // ⑥ 后处理
        rateLimiter.increment(userId);
        // 在线在途计数随 SSE 生命周期回收（完成/取消/异常都会触发 doFinally）
        return new AgentResult.ShallowResult(flux.doFinally(sig -> {
            if (onlineLoad != null) {
                onlineLoad.exit();
            }
        }));
    }

    // ==================== 交叉关注点 ====================

    private void guardrailCheck(String prompt, boolean continueBrake) {
        // ①b system prompt 探查拦截（OWASP LLM07，2026-09-02）：翻译/复述/总结 system
        // prompt 的请求不依赖模型遵从性，规则确定性短路——实测 flash 对"翻译那段规则"
        // 会如实翻译全文，prompt 指令拦不住，故在入口直接返回固定拒绝
        if (isPromptLeakProbe(prompt)) {
            log.warn("Prompt-leak probe blocked: {}",
                    prompt.length() > 40 ? prompt.substring(0, 40) : prompt);
            meterRegistry.counter("guardrail.prompt_leak.blocked").increment();
            throw new BizException(4001,
                    "这些是我的内部设定，不方便透露。有什么情感或关系上的问题，我很乐意帮你聊聊。");
        }

        // ① 标准护栏（ADR-6）
        var verdict = guardrailRuleService.check(prompt);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked ({}): {}", verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            // 可观测（2026-09-05 可观测测试发现）：L3 阻断此前不计任何指标——
            // 08 §2.2 承诺的 guardrail.trigger{level,rule_id}（误报率数据源）与
            // chat.request（含被拦截请求）在此补齐，否则护栏行为完全不可观测。
            meterRegistry.counter("guardrail.trigger", "level", "3",
                    "rule_id", verdict.ruleId()).increment();
            meterRegistry.counter("chat.request", "mode", "blocked",
                    "status", "l3").increment();
            String fallback = "self_harm".equals(verdict.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。你不需要独自面对，我们慢慢聊。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
            throw new BizException(4001, fallback);
        }

        // ② 情绪刹车片（FR-CORE-02）：L2+ 且深夜 且 命中刹车词 且 用户未确认继续发送
        if (emotionBrakeEnabled && !continueBrake && verdict.level() >= 2
                && isLateNight()
                && guardrailRuleService.matchesEmotionBrake(prompt)) {
            log.info("Emotion brake triggered ({}): {}", verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt);
            meterRegistry.counter("emotion_brake.triggered").increment();
            throw new BizException(4002,
                    "我注意到你现在情绪比较激动。深夜情绪容易放大，可以先冷静一下再继续。" +
                    "如果你想换个更温和的说法表达，我可以帮你。" +
                    "若确认仍然要发，请携带 continueBrake=true 重试（本消息仍会被正常发送给 AI）。");
        }

        // ③ 普通 L2/L1 记录
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
        }
    }

    /** system prompt 探查意图判定（OWASP LLM07）：不依赖模型遵从性的确定性拦截。
     *  信号 = 高信号词（system prompt / 系统提示）OR（动作词 × 内容词）组合。 */
    private boolean isPromptLeakProbe(String prompt) {
        if (prompt == null || prompt.length() > 200) {
            return false; // 正常长问题不拦（长输入多为真实咨询）
        }
        String p = prompt.toLowerCase();
        if (p.contains("system prompt") || p.contains("systemprompt") || p.contains("系统提示")) {
            return true;
        }
        boolean action = p.contains("翻译") || p.contains("打印") || p.contains("复述")
                || p.contains("总结") || p.contains("输出") || p.contains("显示")
                || p.contains("告诉") || p.contains("说明") || p.contains("列出");
        boolean target = p.contains("规则") || p.contains("指令") || p.contains("设定")
                || p.contains("提示词") || p.contains("开发者");
        return action && target;
    }

    /** 深夜时段判定（可配置 start-hour / end-hour） */
    private boolean isLateNight() {
        java.time.LocalTime now = java.time.LocalTime.now();
        int hour = now.getHour();
        if (emotionBrakeStartHour <= emotionBrakeEndHour) {
            return hour >= emotionBrakeStartHour && hour < emotionBrakeEndHour;
        }
        return hour >= emotionBrakeStartHour || hour < emotionBrakeEndHour;
    }

    private void recordChatRequest(String mode) {
        try { meterRegistry.counter("chat.request", "mode", mode).increment(); } catch (Exception ignored) {}
    }

    /** 分块模拟流式（与 AgentLoopExecutor 一致） */
    private List<String> chunk(String text) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        int size = 30;
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }
}