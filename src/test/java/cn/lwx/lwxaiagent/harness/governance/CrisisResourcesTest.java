package cn.lwx.lwxaiagent.harness.governance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 危机干预文案的**单一事实源**（ADR-89）。
 *
 * <p>⛔ 这组测试防的是"副本漂移"：此前转介文案有 4 份副本，其中两份**只有一条热线**。
 * 单看任何一处都"没问题"，是**对比**才看得出漏了。所以这里断的是"各出口拿到的是同一份"。</p>
 */
@DisplayName("CrisisResources：热线单一事实源（ADR-89）")
class CrisisResourcesTest {

    @AfterEach
    void restore() {
        CrisisResources.overrideHotlines(CrisisResources.DEFAULT_HOTLINES);
    }

    @Test
    void allOutboundTextsCarryTheSameHotlines() {
        String hot = CrisisResources.hotlines();
        assertThat(hot).as("默认热线清单不该为空").isNotBlank();
        // 输入侧（转介）、输出侧（自伤替换）都必须带上**完整**清单
        assertThat(CrisisResources.referralText()).contains(hot);
        assertThat(CrisisResources.selfHarmOutputText()).contains(hot);
        // 经 GuardrailMessages 的出口（CheckNode / StreamRegistry 走的就是它）
        assertThat(GuardrailMessages.selfHarmOutput()).contains(hot);
        assertThat(GuardrailMessages.forRule("self_harm")).contains(hot);
    }

    /**
     * 默认清单必须是**两条**热线（此前两份副本只写了一条 —— 这就是被修掉的缺陷本身）。
     */
    @Test
    void defaultHotlinesListIsComplete() {
        assertThat(CrisisResources.DEFAULT_HOTLINES)
                .contains("400-161-9995")
                .contains("010-82951332");
    }

    /** 配置覆盖后，**所有**出口同时变（证明它们不是各自一份副本）。 */
    @Test
    void overridePropagatesToEveryExit() {
        CrisisResources.overrideHotlines("测试热线 12345");
        assertThat(CrisisResources.referralText()).contains("测试热线 12345").doesNotContain("400-161-9995");
        assertThat(CrisisResources.selfHarmOutputText()).contains("测试热线 12345").doesNotContain("400-161-9995");
        assertThat(GuardrailMessages.forRule("self_harm")).contains("测试热线 12345");
    }

    /** 配置写空/写错时退回默认：**绝不能让文案里没有热线**。 */
    @Test
    void blankOverrideFallsBackToDefault() {
        CrisisResources.overrideHotlines("   ");
        assertThat(CrisisResources.hotlines()).isEqualTo(CrisisResources.DEFAULT_HOTLINES);
        CrisisResources.overrideHotlines(null);
        assertThat(CrisisResources.hotlines()).isEqualTo(CrisisResources.DEFAULT_HOTLINES);
    }

    /** 通用婉拒文案也只有一份（此前 3 份副本）。 */
    @Test
    void blockTextIsShared() {
        assertThat(GuardrailMessages.OTHER_OUTPUT).isEqualTo(CrisisResources.BLOCK_TEXT);
        assertThat(GuardrailMessages.forRule("harm_others")).isEqualTo(CrisisResources.BLOCK_TEXT);
    }
}
