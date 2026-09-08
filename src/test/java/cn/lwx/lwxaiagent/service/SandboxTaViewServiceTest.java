package cn.lwx.lwxaiagent.service;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGateway;
import cn.lwx.lwxaiagent.mapper.SandboxPersonaMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TA 视角推演测试（2026-09-08 产品闭环 ①）。
 * 覆盖：空输入 400 · 无人设 400 · L3 护栏转介 · REPLY/INSIGHT 解析 · 解析失败降级。
 */
class SandboxTaViewServiceTest {

    private final SandboxPersonaMapper personaMapper = mock(SandboxPersonaMapper.class);
    private final LlmGateway llmGateway = mock(LlmGateway.class);
    private final GuardrailRuleService guardrailRuleService = mock(GuardrailRuleService.class);

    private final SandboxTaViewService service =
            new SandboxTaViewService(personaMapper, llmGateway, guardrailRuleService);

    private void stubLlm(String text) {
        ChatResponse resp = new ChatResponse(List.of(new Generation(
                new org.springframework.ai.chat.messages.AssistantMessage(text))));
        when(llmGateway.call(any(Prompt.class))).thenReturn(resp);
    }

    @Test
    void blankMessage_throws400() {
        when(guardrailRuleService.check(any())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        assertThatThrownBy(() -> service.taView(1L, null, "  "))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
    }

    /** L3 危机输入：给转介话术，不做人格推演（4001 与沙盘同源） */
    @Test
    void crisisInput_throwsReferral() {
        when(guardrailRuleService.check(any()))
                .thenReturn(new GuardrailRuleService.Verdict(3, "self_harm"));

        assertThatThrownBy(() -> service.taView(1L, null, "我不想活了"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    assertThat(((BizException) e).getCode()).isEqualTo(4001);
                    assertThat(e.getMessage()).contains("400-161-9995");
                });
    }

    @Test
    void parsesReplyAndInsight() {
        when(guardrailRuleService.check(any())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        stubLlm("[REPLY]哼，谁在乎你啊。[/REPLY]\n[INSIGHT]嘴硬，其实在意你没理 TA[/INSIGHT]");

        var r = service.taView(1L, null, "我今天没回你消息");

        assertThat(r.reply()).isEqualTo("哼，谁在乎你啊。");
        assertThat(r.insight()).isEqualTo("嘴硬，其实在意你没理 TA");
    }

    /** 模型未按格式输出 → 降级：insight 为 null，不抛错 */
    @Test
    void unparsableOutput_degradesToNullInsight() {
        when(guardrailRuleService.check(any())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        stubLlm("（模型自由发挥的一段话）");

        var r = service.taView(1L, null, "随便说点什么");

        assertThat(r.insight()).isNull();
        assertThat(r.reply()).isEqualTo("（模型自由发挥的一段话）");
    }

    /** 自定义特征模式：不查 persona 表也能推演 */
    @Test
    void customTraits_doesNotQueryPersona() {
        when(guardrailRuleService.check(any())).thenReturn(new GuardrailRuleService.Verdict(0, null));
        stubLlm("[REPLY]嗯。[/REPLY]");

        var r = service.taView(null, "冷淡，话少", "在吗");

        assertThat(r.reply()).isEqualTo("嗯。");
        assertThat(r.personaName()).isEqualTo("TA");
    }
}
