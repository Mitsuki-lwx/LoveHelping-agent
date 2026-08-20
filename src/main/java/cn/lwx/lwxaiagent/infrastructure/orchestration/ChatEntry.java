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

import java.util.List;
import java.util.function.BiConsumer;

/**
 * <h1>聊天唯一入口 —— 能力路由 + 交叉关注点 + 执行分发</h1>
 *
 * <p>所有 HTTP 聊天请求（/chat/*）统一经过此类。职责：</p>
 * <ol>
 *   <li><b>交叉关注点</b>：guardrails、rate limit、metrics（写一次，不散布在各方法）</li>
 *   <li><b>能力路由</b>：{@link CapabilityRouter} 判断需要哪些增强能力 + 多深</li>
 *   <li><b>执行分发</b>：浅层→ChatExecutor；深层→AgentLoopExecutor</li>
 * </ol>
 *
 * <p>Agent 任务（原 /LoveManus）也走此入口——自动升级到 DEEP 深度。</p>
 */
@Slf4j
@Component
public class ChatEntry {

    private final CapabilityRouter router;
    private final ChatExecutor chatExecutor;
    private final AgentLoopExecutor agentExecutor;
    private final ChatService chatService; // 活跃 session 注册
    private final GuardrailRuleService guardrailRuleService;
    private final RateLimiter rateLimiter;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    public ChatEntry(CapabilityRouter router,
                     ChatExecutor chatExecutor,
                     AgentLoopExecutor agentExecutor,
                     ChatService chatService,
                     GuardrailRuleService guardrailRuleService,
                     RateLimiter rateLimiter,
                     io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.router = router;
        this.chatExecutor = chatExecutor;
        this.agentExecutor = agentExecutor;
        this.chatService = chatService;
        this.guardrailRuleService = guardrailRuleService;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
    }

    /**
     * <h3>聊天统一入口</h3>
     *
     * @param message    用户消息
     * @param chatId     会话 ID
     * @param mediaIds   图片 ID 列表（可选，用于多模态）
     * @param forceAgent 是否强制走 Agent 深层模式（/LoveManus 向后兼容）
     * @param taskCallback Agent 任务回调（仅深层模式需要，浅层可 null）
     * @return 执行结果（浅层 Flux / 深层 SseEmitter）
     */
    public AgentResult chat(String message, String chatId,
                            List<Long> mediaIds, boolean forceAgent,
                            BiConsumer<Boolean, String> taskCallback) {

        String userId = TenantContext.getUserId() != null ? TenantContext.getUserId() : "anonymous";

        // ① 交叉关注点
        guardrailCheck(message);
        rateLimiter.checkQuota(userId);

        // ② 路由判断
        CapabilityRouter.RoutingResult routing = router.resolve(message, mediaIds);
        CapabilityRouter.Depth depth = forceAgent ? CapabilityRouter.Depth.DEEP : routing.depth();

        // 填充 CapabilitySet（路由器只给布尔信号，这里注入实际对象）
        CapabilitySet caps = buildCapabilitySet(routing);

        // ③ 指标
        String mode = switch (depth) {
            case SHALLOW -> caps.isEnhanced() ? "enhanced" : "plain";
            case DEEP -> "agent";
        };
        recordChatRequest(mode);

        // ④ 按深度分发
        AgentResult result = switch (depth) {
            case SHALLOW -> chatExecutor.execute(message, chatId, caps);
            case DEEP -> {
                SseEmitter emitter = agentExecutor.run(message, chatId,
                        taskCallback != null ? taskCallback : (ok, err) -> {});
                chatService.registerSession(chatId, emitter);
                // 生命周期：完成/超时/错误时从 activeSessions 移除
                emitter.onCompletion(() -> chatService.unregisterSession(chatId));
                emitter.onTimeout(() -> chatService.unregisterSession(chatId));
                emitter.onError(e -> chatService.unregisterSession(chatId));
                yield new AgentResult.DeepResult(emitter);
            }
        };

        // ⑤ 后处理
        rateLimiter.increment(userId);
        return result;
    }

    /**
     * 根据路由结果构建实际的 CapabilitySet（注入工具/RAG advisor）。
     */
    private CapabilitySet buildCapabilitySet(CapabilityRouter.RoutingResult routing) {
        if (routing.needTools()) {
            return new CapabilitySet(false, chatExecutor.getAllTools(), true, false, false);
        }
        if (routing.needRag()) {
            return new CapabilitySet(true, List.of(), false, false, false);
        }
        return CapabilitySet.plain();
    }

    /**
     * 业务层护栏检查（L3 硬阻断/转介，ADR-6）。
     * 在入口层统一执行，不散布在各执行方法里。
     */
    private void guardrailCheck(String prompt) {
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
        try {
            meterRegistry.counter("chat.request", "mode", mode).increment();
        } catch (Exception ignored) {
        }
    }
}
