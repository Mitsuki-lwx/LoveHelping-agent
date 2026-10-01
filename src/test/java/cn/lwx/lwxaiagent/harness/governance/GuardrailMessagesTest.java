package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站文案的**选择规则**。文案自己漂移不要紧，**选错文案**才要命 ——
 * 例如把"自伤类"的中性措辞(ADR-55 D3)套到别处，或让"有害建议"退回通用婉拒（信息量更低）。
 */
@DisplayName("GuardrailMessages：按 rule_id 选文案")
class GuardrailMessagesTest {

    @Test
    @DisplayName("self_harm* → 自伤类文案（含两条热线），不是通用婉拒")
    void self_harm_gets_referral_style() {
        for (String id : new String[]{"self_harm", "self_harm_incite", "self_harm_exclude"}) {
            assertThat(GuardrailMessages.forRule(id))
                    .as(id)
                    .contains("400-161-9995")
                    .contains("你或你身边的人");
        }
    }

    @Test
    @DisplayName("harmful_advice* → 有害建议专属文案（指向健康处理方式），不是通用婉拒")
    void harmful_advice_gets_specific_text() {
        assertThat(GuardrailMessages.forRule("harmful_advice"))
                .isEqualTo(GuardrailMessages.HARMFUL_ADVICE)
                .contains("暴力、报复或控制行为");
    }

    @Test
    @DisplayName("其余（含 null）→ 通用婉拒；不许把自伤热线乱发给无关场景")
    void others_fall_back_to_generic() {
        for (String id : new String[]{"illegal", "harm_others", "manipulation_intent", null}) {
            assertThat(GuardrailMessages.forRule(id)).as(String.valueOf(id)).isEqualTo(GuardrailMessages.OTHER_OUTPUT);
        }
        assertThat(GuardrailMessages.OTHER_OUTPUT).doesNotContain("400-161-9995");
    }
}
