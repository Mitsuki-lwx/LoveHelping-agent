package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.ai.AgentLoopExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import cn.lwx.lwxaiagent.service.ChatService;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * 聊天统一入口：路由 + 交叉关注点 + 执行分发。
 *
 * 两条路：
 * - 普通：ChatClient 一次 LLM，无工具
 * - Agent：ReactAgent 多步循环，全量工具
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

    public ChatEntry(CapabilityRouter router, ChatExecutor chatExecutor,
                     AgentLoopExecutor agentExecutor, ChatService chatService,
                     GuardrailRuleService guardrailRuleService, RateLimiter rateLimiter,
                     io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.router = router;
        this.chatExecutor = chatExecutor;
        this.agentExecutor = agentExecutor;
        this.chatService = chatService;
        this.guardrailRuleService = guardrailRuleService;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
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

    private void guardrailCheck(String prompt) {
        // ① 情绪刹车片（FR-CORE-02）：深夜+极端情绪→拦截+冷静提示
        if (emotionBrakeCheck(prompt)) {
            log.info("Emotion brake triggered: {}", prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            throw new BizException(4002,
                    "我注意到你现在可能情绪比较激动。深呼吸，我们不着急。" +
                    "如果你想换个更温和的方式表达，我可以帮你。");
        }

        // ② 标准护栏（ADR-6）
        var verdict = guardrailRuleService.check(prompt);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked ({}): {}", verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
            String fallback = "self_harm".equals(verdict.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。你不需要独自面对，我们慢慢聊。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
            throw new BizException(4001, fallback);
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(),
                    prompt.length() > 30 ? prompt.substring(0, 30) : prompt);
        }
    }

    private void recordChatRequest(String mode) {
        try { meterRegistry.counter("chat.request", "mode", mode).increment(); } catch (Exception ignored) {}
    }

    /**
     * 情绪刹车片（FR-CORE-02）：深夜时段(23:00-06:00) + 极端情绪关键词 → 触发冷静提示。
     * 只对用户输出生效（拦截冲动发言），不改变 AI 回复基调。
     */
    private boolean emotionBrakeCheck(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;

        // 深夜时段
        LocalTime now = LocalTime.now();
        boolean lateNight = now.getHour() >= 23 || now.getHour() < 6;
        if (!lateNight) return false;

        // 极端情绪关键词
        return EMOTION_KEYWORDS.matcher(prompt).find();
    }

    /** 极端情绪关键词（深夜触发） */
    private static final Pattern EMOTION_KEYWORDS = Pattern.compile(
            "(?i)(分手|你从来|你永远|滚|去死|恨|受不了|崩溃|绝望|想死|自杀|完了|废物|傻逼|fuck|shit|hate)");
}