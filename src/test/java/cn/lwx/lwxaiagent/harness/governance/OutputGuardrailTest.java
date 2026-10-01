package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>输出侧护栏 —— 「最后防线」（此前覆盖率 14.3%）</h3>
 *
 * <p>它是模型输出**外发前**的最后一道检查，两层：</p>
 * <ol>
 *   <li><b>危机应答缺失</b>：用户提到自伤，而模型回复里没有任何危机干预内容（热线/专业/求助/心理）
 *       → 必须拦掉并换成危机文案。⛔ 反过来同样重要：**回复里已经给了危机资源就不能拦**，
 *       否则每一次真正的危机对话都会被替换成模板话术（那是最坏的一种"安全"）。</li>
 *   <li><b>有害建议**：输出里出现"你应该打/报复他/以牙还牙"这类 —— 亲密关系语境下直接拦。</li>
 * </ol>
 * <p>判据必须是**两个方向的**：该拦的拦得住、不该拦的别误伤。只测一个方向的门是假门。</p>
 */
@DisplayName("OutputGuardrail：最后的输出防线")
class OutputGuardrailTest {

    private final OutputGuardrail guardrail = new OutputGuardrail();

    @Test
    @DisplayName("空/null 输出不算危险（没东西可外发）")
    void blank_output_passes() {
        assertThat(guardrail.check(null, "我想死").blocked()).isFalse();
        assertThat(guardrail.check("   ", "我想死").blocked()).isFalse();
    }

    @Test
    @DisplayName("⛔ 用户提到自伤、而回复里没有任何危机应答 → 必须拦，并给危机文案")
    void crisis_input_without_crisis_response_is_blocked() {
        GuardrailResult r = guardrail.check("嗯，那你要不要先冷静一下，我们聊聊别的。", "我最近总是想死");

        assertThat(r.blocked()).as("这是「最后防线」的核心场景：模型漏了危机应答").isTrue();
        assertThat(r.reason()).isEqualTo("missing_crisis_response");
        assertThat(r.fallback()).as("拦下来必须有替代文案，不能只是拒绝").isNotBlank();
    }

    @Test
    @DisplayName("⛔ 反向：回复里**已经给了**危机资源 → 不许拦（否则真危机对话全被模板替换）")
    void crisis_input_with_crisis_response_passes() {
        String good = "我听到你说想死，这很沉重。请一定联系全国心理援助热线 400-161-9995，"
                + "或寻求专业帮助。你不需要一个人扛。";
        assertThat(guardrail.check(good, "我最近总是想死").blocked())
                .as("误拦的代价：真正有用的回复被换成模板")
                .isFalse();
        // 四个标记词逐个验证（少一个都可能让某类正确回复被误拦）
        for (String marker : new String[]{"热线", "专业", "求助", "心理"}) {
            assertThat(guardrail.check("你可以" + marker + "看看。", "我想死").blocked())
                    .as("含「" + marker + "」即视为已给危机应答").isFalse();
        }
    }

    @Test
    @DisplayName("⚠️ 有害建议**已搬走**（V30 起由规则表 scope=OUTPUT 管辖）—— 本类不再拦它")
    void harmful_advice_is_no_longer_handled_here() {
        // ⛔ 这条**故意钉住搬迁结果**：本类只剩"危机应答缺失"一层。
        //    若有人把关键词表加回来，这里会红，提醒你先读 V30 与 ADR-6（规则外置）。
        assertThat(guardrail.check("你可以报复他，让他也尝尝滋味", "他老是骗我").blocked())
                .as("搬到 guardrail_rule 了；两路径（流式 StreamSink / 非流式 advisor）都用那一份")
                .isFalse();
    }

    @Test
    @DisplayName("普通对话不误伤（既没危机信号、也没有害建议）")
    void ordinary_dialogue_passes() {
        assertThat(guardrail.check("听起来你有点难过，愿意说说发生了什么吗？", "他今天没回我消息").blocked())
                .isFalse();
    }
}
