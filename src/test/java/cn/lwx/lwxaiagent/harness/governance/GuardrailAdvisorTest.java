package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>护栏裁决的**伦理红线**（此前该类覆盖率 0%）</h3>
 *
 * <p>这类代码是全系统唯一"用户可见行为由规则决定"的地方（SRS §5.3 伦理红线），
 * 而它此前**一行都没被测过**。本测试钉四件事：</p>
 * <ol>
 *   <li>L3 且 {@code ruleId=self_harm} → 给的是**转介文案**（含心理援助热线），不是通用拒绝；</li>
 *   <li>L3 但其他规则 → 给**通用阻断文案**（不误导成"你要去看心理医生"）；</li>
 *   <li>L1/L2 → **放行**，只记账（不能把提示级误当阻断，那会把产品变成一问三拒）；</li>
 *   <li>⛔ **输出侧命中时必须替换掉模型原文**（2026-09-05 的中危修复：此前仅记日志，
 *       '最后防线'形同虚设）—— 这条最容易在重构里被悄悄改回去。</li>
 * </ol>
 */
@DisplayName("GuardrailAdvisor：裁决与兜底文案")
class GuardrailAdvisorTest {

    private OutputGuardrail outputGuardrail;
    private GuardrailRuleService ruleService;
    private GuardrailEventRecorder recorder;
    private GuardrailAdvisor advisor;

    @BeforeEach
    void setUp() {
        outputGuardrail = mock(OutputGuardrail.class);
        ruleService = mock(GuardrailRuleService.class);
        recorder = mock(GuardrailEventRecorder.class);
        advisor = new GuardrailAdvisor(outputGuardrail, ruleService, recorder);
        when(outputGuardrail.check(any(), any())).thenReturn(new GuardrailResult(false, null, null, false));
        // ⛔ 2026-10-01（V30）：输出侧 L3 现在由**规则层**承担（scope=OUTPUT），
        //    与流式 StreamSink 同一份规则。默认"未命中"，否则未 stub 会返回 null 触发 NPE。
        when(ruleService.check(anyString(), eq(GuardrailRuleService.Scope.OUTPUT)))
                .thenReturn(new GuardrailRuleService.Verdict(0, null));
    }

    private static ChatClientRequest request(String userText) {
        return new ChatClientRequest(new Prompt(userText), Map.of());
    }

    private static ChatClientResponse response(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text))), new ChatResponseMetadata()),
                Map.of());
    }

    private static String textOf(ChatClientResponse r) {
        return r.chatResponse().getResult().getOutput().getText();
    }

    private CallAdvisorChain chainReturning(String text) {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(text));
        return chain;
    }

    @Test
    @DisplayName("L3 + self_harm → **转介文案（含援助热线）**，并记 BLOCKED，且不调用下游")
    void l3_self_harm_returns_referral() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(3, "self_harm"));
        CallAdvisorChain chain = chainReturning("模型输出");

        ChatClientResponse out = advisor.adviseCall(request("我想死"), chain);

        assertThat(textOf(out))
                .as("自伤信号给的是转介（07 §5：动作是转介，不是治疗）")
                .contains("400-161-9995");
        verify(recorder).record(anyString(), eq(3), eq("self_harm"), eq("BLOCKED"));
        verify(chain, never()).nextCall(any());
    }

    @Test
    @DisplayName("L3 + 其他规则 → **通用阻断文案**（不要误给心理热线）")
    void l3_other_rule_returns_block_text() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(3, "illegal"));
        CallAdvisorChain chain = chainReturning("模型输出");

        ChatClientResponse out = advisor.adviseCall(request("教我点什么"), chain);

        assertThat(textOf(out)).doesNotContain("400-161-9995").contains("这个话题涉及的内容我不能帮你处理");
        verify(recorder).record(anyString(), eq(3), eq("illegal"), eq("BLOCKED"));
        verify(chain, never()).nextCall(any());
    }

    @Test
    @DisplayName("L1/L2 → **放行**到下游，只记 LOGGED（提示级不能变成阻断）")
    void soft_levels_pass_through() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(2, "emotion_brake"));
        CallAdvisorChain chain = chainReturning("正常的共情回复");

        ChatClientResponse out = advisor.adviseCall(request("我今天有点低落"), chain);

        assertThat(textOf(out)).as("L2 是降温不是阻断，正文必须原样透出").isEqualTo("正常的共情回复");
        verify(recorder).record(anyString(), eq(2), eq("emotion_brake"), eq("LOGGED"));
        verify(chain).nextCall(any());
    }

    @Test
    @DisplayName("⛔ 输出侧命中 → **必须替换掉模型原文**，且用**本层自己的文案**（不是通用婉拒）")
    void blocked_output_is_replaced_with_layer_specific_fallback() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(outputGuardrail.check(any(), any()))
                .thenReturn(new GuardrailResult(true, "missing_crisis_response",
                        "危机文案：请联系全国心理援助热线 400-161-9995", false));
        CallAdvisorChain chain = chainReturning("嗯，那你先冷静一下，我们聊点别的。");

        ChatClientResponse out = advisor.adviseCall(request("我最近总是想死"), chain);

        assertThat(textOf(out))
                .as("命中的输出绝不能外发 —— 这是 2026-09-05 修掉的中危缺陷")
                .doesNotContain("冷静一下");
        assertThat(textOf(out))
                .as("⛔ 2026-10-01 修正：原先一律用通用婉拒，把危机资源丢了 —— 该场景最需要热线")
                .contains("400-161-9995");
    }

    @Test
    @DisplayName("⛔ 输出侧 L3 规则（scope=OUTPUT）：与非流式/流式**同一份规则**，命中即替换")
    void output_scope_rule_hit_is_replaced() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(ruleService.check(anyString(), eq(GuardrailRuleService.Scope.OUTPUT)))
                .thenReturn(new GuardrailRuleService.Verdict(3, "harmful_advice"));
        CallAdvisorChain chain = chainReturning("你可以报复他，让他也尝尝这滋味");

        ChatClientResponse out = advisor.adviseCall(request("他老是骗我"), chain);

        assertThat(textOf(out))
                .as("V30 起这层走规则表；文案由 GuardrailMessages 按 rule_id 选")
                .doesNotContain("报复他")
                .contains("我无法提供此类建议");
    }

    @Test
    @DisplayName("输出侧干净 → 原样返回下游响应（不改变正常回复）")
    void clean_output_passes_unchanged() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        CallAdvisorChain chain = chainReturning("先说说当时发生了什么？");

        ChatClientResponse out = advisor.adviseCall(request("他老是回消息很慢"), chain);

        assertThat(textOf(out)).isEqualTo("先说说当时发生了什么？");
        verify(recorder, never()).record(anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), anyString());
    }

    // ── 流式路径（此前未覆盖）─────────────────────────────────────────

    private StreamAdvisorChain streamChainOf(String... texts) {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.fromArray(texts).map(GuardrailAdvisorTest::response));
        return chain;
    }

    @Test
    @DisplayName("流式 L3 + self_harm → 只发转介文案，且**不订阅下游**（不能先流出去再后悔）")
    void stream_l3_blocks_without_subscribing() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(3, "self_harm"));
        StreamAdvisorChain chain = streamChainOf("模型输出");

        List<ChatClientResponse> out = advisor.adviseStream(request("我想死"), chain).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(textOf(out.get(0))).contains("400-161-9995");
        verify(recorder).record(anyString(), eq(3), eq("self_harm"), eq("BLOCKED"));
        verify(chain, never()).nextStream(any());
    }

    @Test
    @DisplayName("流式 L2 → 放行到下游，只记 LOGGED")
    void stream_soft_level_passes_through() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(2, "emotion_brake"));
        StreamAdvisorChain chain = streamChainOf("正常回复");

        List<ChatClientResponse> out = advisor.adviseStream(request("有点低落"), chain).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(textOf(out.get(0))).isEqualTo("正常回复");
        verify(recorder).record(anyString(), eq(2), eq("emotion_brake"), eq("LOGGED"));
    }

    /**
     * ⛔ 这条**故意钉住"事后只告警"**：流式的聚合回调在**流结束**时执行，文本早已送达，
     * 改不了 —— 这是结构限制，不是漏写。真正的逐块拦截在 {@code StreamRegistry.StreamSink}
     * （按 DB 规则的 Scope.OUTPUT 丢弃并改推文案）。
     *
     * <p>将来若给流式补"事后追发纠正文案"，**请先改这条测试**：它会提醒你这是一次有意的行为变更，
     * 而不是顺手改绿。</p>
     */
    @Test
    @DisplayName("流式：输出侧事后命中**只告警不改内容**（结构限制；逐块拦截在 StreamRegistry）")
    void stream_output_hit_only_warns() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(outputGuardrail.check(any(), any()))
                .thenReturn(new GuardrailResult(true, "missing_crisis_response", "危机文案", false));
        StreamAdvisorChain chain = streamChainOf("已经发出去的回复");

        List<ChatClientResponse> out = advisor.adviseStream(request("我最近总是想死"), chain).collectList().block();

        assertThat(out).hasSize(1);
        assertThat(textOf(out.get(0)))
                .as("流式无法撤回已发出的文本；改这条前先想清楚要不要做事后追发")
                .isEqualTo("已经发出去的回复");
    }

    @Test
    @DisplayName("本 advisor 必须在链末（order = MAX_VALUE），否则会绕过其他 advisor")
    void order_is_last() {
        assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }
}
