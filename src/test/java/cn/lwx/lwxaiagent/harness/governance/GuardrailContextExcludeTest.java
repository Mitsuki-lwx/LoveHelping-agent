package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.entity.GuardrailRule;
import cn.lwx.lwxaiagent.mapper.GuardrailRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 护栏规则"前提/豁免式"单测（ADR-59 / V28）。
 *
 * <p>核心不变量：**豁免只作用于它自己那条规则**，且**失败方向偏严**——
 * 豁免式写坏了、或没写，规则照常拦；绝不会因为豁免机制把该拦的放过。</p>
 */
class GuardrailContextExcludeTest {

    private static GuardrailRule rule(String ruleId, int level, String type, String pattern,
                                      String scope, String exclude) {
        GuardrailRule r = new GuardrailRule();
        r.setRuleId(ruleId);
        r.setLevel(level);
        r.setPatternType(type);
        r.setPattern(pattern);
        r.setEnabled(true);
        r.setScope(scope);
        r.setContextExclude(exclude);
        return r;
    }

    private static GuardrailRuleService serviceWith(GuardrailRule... rules) {
        GuardrailRuleMapper mapper = mock(GuardrailRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(rules));
        GuardrailRuleService svc = new GuardrailRuleService(mapper);
        svc.load();
        return svc;
    }

    @Test
    @DisplayName("豁免式命中 → 本规则不算命中（第三人称转述不再被拦）")
    void excludeHitSuppressesRule() {
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "朋友[^。！？\\n]{0,6}自杀"));
        assertEquals(0, svc.check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("豁免式未命中 → 规则照常命中（第一人称风险一条都不许漏）")
    void excludeMissKeepsRule() {
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "朋友[^。！？\\n]{0,6}自杀"));
        assertEquals(3, svc.check("我想自杀", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("豁免只作用于自己那条规则 —— 别的 L3 规则不得被一起放过")
    void excludeDoesNotLeakToOtherRules() {
        var svc = serviceWith(
                rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "朋友[^。！？\\n]{0,6}自杀"),
                rule("other", 3, "KEYWORD", "自杀", "INPUT", null));
        assertEquals(3, svc.check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("无豁免式（null / 空串）→ 行为与加字段前逐字一致")
    void noExcludeBehavesAsBefore() {
        assertEquals(3, serviceWith(rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", null))
                .check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level());
        assertEquals(3, serviceWith(rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "  "))
                .check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("⛔ 豁免式正则非法 → 降级为「不豁免」（失败必须偏严，不得静默放过）")
    void invalidExcludeFailsSafe() {
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "朋友(["));
        assertEquals(3, svc.check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("豁免生效但等级更高的另一条规则仍在 → 取最高级不受豁免影响")
    void excludeOnlyRemovesThatRuleFromLevelComputation() {
        var svc = serviceWith(
                rule("self_harm", 3, "KEYWORD", "自杀", "INPUT", "朋友[^。！？\\n]{0,6}自杀"),
                rule("soft", 2, "KEYWORD", "自杀", "INPUT", "朋友[^。！？\\n]{0,6}自杀"));
        assertEquals(0, svc.check("朋友说他想自杀", GuardrailRuleService.Scope.INPUT).level(),
                "两条都被各自豁免后应为 0");
    }
}
