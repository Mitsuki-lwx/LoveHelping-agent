package cn.lwx.lwxaiagent.harness.governance;



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


/**
 * <h1>安全护栏拦截器（ADR-6 升级：规则外置 + 三级梯度 + 事件审计）</h1>
 *
 * <p>执行顺序 {@code Integer.MAX_VALUE}（最内层，发送给 LLM 前最后一道输入检查）：</p>
 * <ul>
 *   <li><b>L3 硬阻断</b>：自伤（转介援助资源）/ 伤人 / 违法 / PUA 教学 / Prompt 注入 → 不调 LLM，返回阻断文案，记 event(BLOCKED)</li>
 *   <li><b>L1/L2 软处理</b>：模糊输入 / 辱骂 → 不阻断，记 event(LOGGED)（降温文案注入随 loop 重构完善）</li>
 *   <li><b>输出侧（非流式）</b>：{@link OutputGuardrail} 两层 —— ①用户提到自伤而回复缺危机应答 ②输出含有害建议；
 *       <b>命中即替换文案，不得外发</b>（2026-09-05 中危修复：此前仅记日志，"最后防线"形同虚设）</li>
 *   <li><b>输出侧（流式）</b>：⚠️ 本类只能**事后告警**（见 {@link #adviseStream}），
 *       <b>真正的逐块拦截在 {@code StreamRegistry.StreamSink}</b>（按 DB 规则的 {@code Scope.OUTPUT}）。
 *       ⛔ 两层判据的覆盖差异见 {@link #adviseStream} 的注释 —— 别把这里当"流式也被兜住了"</li>
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
    private final GuardrailEventRecorder recorder;

    public GuardrailAdvisor(OutputGuardrail outputGuardrail,
                            GuardrailRuleService ruleService,
                            GuardrailEventRecorder recorder) {
        this.outputGuardrail = outputGuardrail;
        this.ruleService = ruleService;
        this.recorder = recorder;
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
            recorder.record(userText, verdict.level(), verdict.ruleId(), "BLOCKED");
            String fallback = "self_harm".equals(verdict.ruleId()) ? REFERRAL_TEXT : BLOCK_TEXT;
            return fallbackResponse(fallback);
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged ({}): {}", verdict.level(), verdict.ruleId(), truncate(userText));
            recorder.record(userText, verdict.level(), verdict.ruleId(), "LOGGED");
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
            recorder.record(userText, verdict.level(), verdict.ruleId(), "BLOCKED");
            String fallback = "self_harm".equals(verdict.ruleId()) ? REFERRAL_TEXT : BLOCK_TEXT;
            return Flux.just(fallbackResponse(fallback));
        }
        if (verdict.level() > 0) {
            log.info("Guardrail L{} logged (stream) ({}): {}", verdict.level(), verdict.ruleId(), truncate(userText));
            recorder.record(userText, verdict.level(), verdict.ruleId(), "LOGGED");
        }
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        // ⚠️ 这里**只能告警**，不是漏写：aggregateChatClientResponse 在**流结束**时回调，
        //    那时文本已经发给用户了，改不了。**逐块拦截在 StreamRegistry.StreamSink**：
        //    它按 DB 规则的 Scope.OUTPUT 判定，命中即丢弃该块并改推替换文案（含跨 chunk 窗口）。
        //
        // ⛔ 于是两条路径的**判据覆盖不同**（2026-10-01 核查，此前只有类注释一句含糊的"仅告警"）：
        //    · 非流式 adviseCall：DB 规则 L3 + OutputGuardrail 两层 → **两层都能替换**；
        //    · 流式：只有 DB 规则 L3 会被**拦住**；OutputGuardrail 两层里
        //      - missing_crisis_response（要"整段输出 + 用户输入"才判得出）→ 结构上做不到，只能事后告警；
        //      - harmful_advice（**纯文本关键词**，本可以逐块拦）→ **目前没拦**，两路径不对称（已登记待决）。
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(responses, aggregated -> {
                    String outputText = getOutputText(aggregated);
                    GuardrailResult outputCheck = outputGuardrail.check(outputText, userText);
                    if (outputCheck.blocked()) {
                        log.warn("Output guardrail (stream, 事后告警——已无法撤回): {}", outputCheck.reason());
                    }
                });
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
