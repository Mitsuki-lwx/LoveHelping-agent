package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.ai.AgentLoopExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.lwx.lwxaiagent.service.ChatService;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 聊天唯一入口：路由 + 交叉关注点 + 执行分发。
 * 两条路径：不需要工具 → ChatExecutor；需要工具 → AgentLoopExecutor（ReactAgent 多步循环）。
 */
@Slf4j
@Component
public class ChatEntry {

    private final CapabilityRouter router;
    private final ChatExecutor chatExecutor;
    private final AgentLoopExecutor agentExecutor;
    private final ChatService chatService;
    private final GuardrailRuleService guardrailRuleService;
    private final RateLimiter rateLimiter;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /** 情绪刹车片配置（FR-CORE-02） */
    private final boolean emotionBrakeEnabled;
    private final int emotionBrakeStartHour;
    private final int emotionBrakeEndHour;

    public ChatEntry(CapabilityRouter router, ChatExecutor chatExecutor,
                     AgentLoopExecutor agentExecutor, ChatService chatService,
                     GuardrailRuleService guardrailRuleService, RateLimiter rateLimiter,
                     io.micrometer.core.instrument.MeterRegistry meterRegistry,
                     @Value("${app.emotion-brake.enabled:true}") boolean emotionBrakeEnabled,
                     @Value("${app.emotion-brake.start-hour:23}") int emotionBrakeStartHour,
                     @Value("${app.emotion-brake.end-hour:6}") int emotionBrakeEndHour) {
        this.router = router;
        this.chatExecutor = chatExecutor;
        this.agentExecutor = agentExecutor;
        this.chatService = chatService;
        this.guardrailRuleService = guardrailRuleService;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.emotionBrakeEnabled = emotionBrakeEnabled;
        this.emotionBrakeStartHour = emotionBrakeStartHour;
        this.emotionBrakeEndHour = emotionBrakeEndHour;
    }

    public AgentResult chat(String message, String chatId, List<Long> mediaIds,
                            boolean forceAgent, BiConsumer<Boolean, String> taskCallback) {
        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";

        // ① 交叉关注点
        guardrailCheck(message);
        rateLimiter.checkQuota(userId);

        // ② 路由：需不需要工具？
        boolean needTools = forceAgent || router.needTools(message, mediaIds);

        // ③ 指标
        recordChatRequest(needTools ? "agent" : "plain");

        // ④ 分发
        AgentResult result;
        if (needTools) {
            SseEmitter emitter = agentExecutor.run(message, chatId,
                    taskCallback != null ? taskCallback : (ok, err) -> {});
            chatService.registerSession(chatId, emitter);
            emitter.onCompletion(() -> chatService.unregisterSession(chatId));
            emitter.onTimeout(() -> chatService.unregisterSession(chatId));
            emitter.onError(e -> chatService.unregisterSession(chatId));
            result = new AgentResult.DeepResult(emitter);
        } else {
            result = chatExecutor.execute(message, chatId, CapabilitySet.plain());
        }

        // ⑤ 后处理
        rateLimiter.increment(userId);
        return result;
    }

    // ==================== 交叉关注点 ====================

    private void guardrailCheck(String prompt) {
        // ① 标准护栏（ADR-6）
        var verdict = guardrailRuleService.check(prompt);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked ({}): {}", verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            String fallback = "self_harm".equals(verdict.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。你不需要独自面对，我们慢慢聊。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
            throw new BizException(4001, fallback);
        }

        // ② 情绪刹车片（FR-CORE-02）：L2 级 + 深夜时段 + emotion_brake 规则
        if (emotionBrakeEnabled && verdict.level() >= 2
                && verdict.ruleId() != null && verdict.ruleId().startsWith("emotion_brake_")
                && isLateNight()) {
            log.info("Emotion brake triggered ({}): {}", verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt);
            meterRegistry.counter("emotion_brake.triggered").increment();
            throw new BizException(4002,
                    "我注意到你现在情绪比较激动。深夜情绪容易放大，可以先冷静一下再继续。" +
                    "如果你想换个更温和的说法表达，我可以帮你。你也可以选择【继续发送】。");
        }

        // ③ 普通 L2/L1 记录
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
        }
    }

    /** 深夜时段判定（可配置 start-hour / end-hour） */
    private boolean isLateNight() {
        java.time.LocalTime now = java.time.LocalTime.now();
        int hour = now.getHour();
        if (emotionBrakeStartHour <= emotionBrakeEndHour) {
            return hour >= emotionBrakeStartHour && hour < emotionBrakeEndHour;
        }
        // 跨午夜：23:00-06:00 → hour >= 23 || hour < 6
        return hour >= emotionBrakeStartHour || hour < emotionBrakeEndHour;
    }

    private void recordChatRequest(String mode) {
        try { meterRegistry.counter("chat.request", "mode", mode).increment(); } catch (Exception ignored) {}
    }
}
