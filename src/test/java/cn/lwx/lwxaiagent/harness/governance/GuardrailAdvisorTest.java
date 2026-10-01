package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
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
    @DisplayName("⛔ 输出侧命中 → **必须替换掉模型原文**（最后防线，不是只记日志）")
    void blocked_output_is_replaced_not_merely_logged() {
        when(ruleService.check(anyString())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        when(outputGuardrail.check(any(), any()))
                .thenReturn(new GuardrailResult(true, "输出含操控话术", null, false));
        CallAdvisorChain chain = chainReturning("你应该这样拿捏对方……");

        ChatClientResponse out = advisor.adviseCall(request("怎么回复"), chain);

        assertThat(textOf(out))
                .as("命中的输出绝不能外发 —— 这是 2026-09-05 修掉的中危缺陷")
                .doesNotContain("拿捏")
                .contains("这个话题涉及的内容我不能帮你处理");
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

    @Test
    @DisplayName("本 advisor 必须在链末（order = MAX_VALUE），否则会绕过其他 advisor")
    void order_is_last() {
        assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE);
    }
}
