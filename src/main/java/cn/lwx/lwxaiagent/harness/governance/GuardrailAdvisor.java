package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.entity.GuardrailEvent;
import cn.lwx.lwxaiagent.mapper.GuardrailEventMapper;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * <h1>安全护栏拦截器（ADR-6 升级：规则外置 + 三级梯度 + 事件审计）</h1>
 *
 * <p>执行顺序 {@code Integer.MAX_VALUE}（最内层，发送给 LLM 前最后一道输入检查）：</p>
 * <ul>
 *   <li><b>L3 硬阻断</b>：自伤（转介援助资源）/ 伤人 / 违法 / PUA 教学 / Prompt 注入 → 不调 LLM，返回阻断文案，记 event(BLOCKED)</li>
 *   <li><b>L1/L2 软处理</b>：模糊输入 / 辱骂 → 不阻断，记 event(LOGGED)（降温文案注入随 loop 重构完善）</li>
 *   <li><b>输出侧</b>：保留自伤信号检测（OutputGuardrail），命中仅告警不阻断生成</li>
 * </ul>
 * <p>规则存 DB（guardrail_rule），改动不发版；审计只存 content_hmac 不存原文（07 §6）。</p>
 */
@Slf4j
@Component
public class GuardrailAdvisor implements CallAdvisor, StreamAdvisor {

    /** 心理援助转介文案（07 §5：检测到自伤信号的动作是转介，不是治疗） */
    private static final String REFERRAL_TEXT = """
            我注意到你现在的状态可能非常难受。我想先让你知道：你的感受是真实的，也值得被认真对待。
            如果你正在经历难以承受的时刻，请一定联系专业援助——你不需要独自面对：
            全国心理援助热线：400-161-9995
            北京心理危机研究与干预中心：010-82951332
            我在你身边，但专业帮助能给你更稳妥的支持。要不要先深呼吸几次，我们再慢慢聊？
            """;

    /** 通用 L3 阻断文案 */
    private static final String BLOCK_TEXT = "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";

    private final OutputGuardrail outputGuardrail;
    private final GuardrailRuleService ruleService;
    private final GuardrailEventMapper eventMapper;

    public GuardrailAdvisor(OutputGuardrail outputGuardrail,
                            GuardrailRuleService ruleService,
                            GuardrailEventMapper eventMapper) {
        this.outputGuardrail = outputGuardrail;
        this.ruleService = ruleService;
        this.eventMapper = eventMapper;
        log.info("GuardrailAdvisor bean created");
    }

    @Override
    public String getName() {
        return "GuardrailAdvisor";
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = getUserText(request);
        log.debug("Guardrail adviseCall input len={}: {}", userText.length(), truncate(userText));
        GuardrailRuleService.Verdict verdict = ruleService.check(userText);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked ({}): {}", verdict.ruleId(), truncate(userText));
            recordEvent(userText, verdict.level(), verdict.ruleId(), "BLOCKED");
            String fallback = "self_harm".equals(verdict.ruleId()) ? REFERRAL_TEXT : BLOCK_TEXT;
            return fallbackResponse(fallback);
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(), truncate(userText));
            recordEvent(userText, verdict.level(), verdict.ruleId(), "LOGGED");
        }

        ChatClientResponse response = chain.nextCall(request);
        String outputText = getOutputText(response);
        GuardrailResult outputCheck = outputGuardrail.check(outputText, userText);
        if (outputCheck.blocked()) {
            log.warn("Output guardrail: {}", outputCheck.reason());
            // 中危修复（2026-09-05）：blocked 输出不得外发——替换为通用引导话术
            // （此前仅记日志，'最后防线'形同虚设；agent/非流路径均走本方法）
            return fallbackResponse(BLOCK_TEXT);
        }
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String userText = getUserText(request);
        GuardrailRuleService.Verdict verdict = ruleService.check(userText);
        if (verdict.level() >= 3) {
            log.warn("Guardrail L3 blocked (stream) ({}): {}", verdict.ruleId(), truncate(userText));
            recordEvent(userText, verdict.level(), verdict.ruleId(), "BLOCKED");
            String fallback = "self_harm".equals(verdict.ruleId()) ? REFERRAL_TEXT : BLOCK_TEXT;
            return Flux.just(fallbackResponse(fallback));
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged (stream) ({}): {}", verdict.level(), verdict.ruleId(), truncate(userText));
            recordEvent(userText, verdict.level(), verdict.ruleId(), "LOGGED");
        }

        Flux<ChatClientResponse> responses = chain.nextStream(request);
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(responses, aggregated -> {
                    String outputText = getOutputText(aggregated);
                    GuardrailResult outputCheck = outputGuardrail.check(outputText, userText);
                    if (outputCheck.blocked()) {
                        log.warn("Output guardrail (stream): {}", outputCheck.reason());
                    }
                });
    }

    /** 审计事件：只存 content_hmac（07 §6），不存原文 */
    private void recordEvent(String userText, int level, String ruleId, String action) {
        try {
            GuardrailEvent event = new GuardrailEvent();
            event.setUserId(TenantContext.getUserId());
            event.setLevel(level);
            event.setRuleId(ruleId);
            event.setContentHmac(sha256(userText));
            event.setAction(action);
            event.setCreatedAt(LocalDateTime.now());
            eventMapper.insert(event);
        } catch (Exception e) {
            log.warn("Failed to record guardrail event: {}", e.getMessage());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getUserText(ChatClientRequest request) {
        try {
            var userMsg = request.prompt().getUserMessage();
            if (userMsg == null) {
                log.error("Guardrail fail-closed: cannot extract user message from prompt");
                return "";
            }
            return userMsg.getText();
        } catch (Exception e) {
            log.error("Guardrail fail-closed: getUserText error: {}", e.getMessage());
            return "";
        }
    }

    private String getOutputText(ChatClientResponse response) {
        try {
            return response.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            return "";
        }
    }

    private String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }

    private ChatClientResponse fallbackResponse(String text) {
        ChatResponse chatResponse = new ChatResponse(
                java.util.List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(chatResponse, java.util.Map.of());
    }
}
