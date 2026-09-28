package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.entity.GuardrailRule;
import cn.lwx.lwxaiagent.mapper.GuardrailRuleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 护栏规则"适用范围"单测（ADR-55）。
 *
 * <p>核心不变量：**既有规则（scope=BOTH）在两侧行为都不变**（这是加字段的回归底线），
 * 而 INPUT-only / OUTPUT-only 的规则**只在本侧生效**。</p>
 */
class GuardrailRuleScopeTest {

    private static GuardrailRule rule(String ruleId, int level, String type, String pattern, String scope) {
        GuardrailRule r = new GuardrailRule();
        r.setRuleId(ruleId);
        r.setLevel(level);
        r.setPatternType(type);
        r.setPattern(pattern);
        r.setEnabled(true);
        r.setScope(scope);
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
    @DisplayName("INPUT-only 规则：输入侧命中，输出侧不命中")
    void inputOnlyRuleDoesNotFireOnOutput() {
        // 这就是 V25 对 self_harm 裸关键词做的改动
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "伤害自己", "INPUT"));
        assertEquals(3, svc.check("你最近有没有想过伤害自己？", GuardrailRuleService.Scope.INPUT).level());
        assertEquals(0, svc.check("你最近有没有想过伤害自己？", GuardrailRuleService.Scope.OUTPUT).level(),
                "输出侧的『专业地提到自伤』不得被拦 —— 这是 ADR-55 要修的核心缺陷");
    }

    @Test
    @DisplayName("OUTPUT-only 规则：输出侧命中，输入侧不命中")
    void outputOnlyRuleDoesNotFireOnInput() {
        // 与 V25 的 self_harm_incite 同形（"建议/教"类词 + 自伤词，同句相邻）
        var svc = serviceWith(rule("self_harm_incite", 3, "REGEX",
                "(?:你可以?|教你|建议你?)[^。！？]{0,8}(?:伤害自己|自杀)", "OUTPUT"));
        assertEquals(3, svc.check("教你伤害自己的几种方法", GuardrailRuleService.Scope.OUTPUT).level(),
                "输出侧的『教唆』必须命中");
        assertEquals(0, svc.check("教你伤害自己的几种方法", GuardrailRuleService.Scope.INPUT).level(),
                "同一句话在输入侧不该命中这条 OUTPUT-only 规则");
    }

    @Test
    @DisplayName("BOTH（默认）：两侧都命中 —— 既有规则行为逐字不变（回归底线）")
    void bothScopeFiresOnBothSides() {
        for (String scope : new String[]{"BOTH", null, "", "bogus"}) {
            var svc = serviceWith(rule("manipulation_intent", 3, "KEYWORD", "PUA", scope));
            assertEquals(3, svc.check("教我PUA她", GuardrailRuleService.Scope.INPUT).level(),
                    "scope=" + scope + " 时输入侧应命中（默认 BOTH）");
            assertEquals(3, svc.check("教我PUA她", GuardrailRuleService.Scope.OUTPUT).level(),
                    "scope=" + scope + " 时输出侧应命中（默认 BOTH）");
        }
    }

    @Test
    @DisplayName("无参 check() 等价 INPUT（兼容重载的语义必须钉住）")
    void noArgCheckMeansInput() {
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "伤害自己", "INPUT"));
        assertEquals(svc.check("伤害自己", GuardrailRuleService.Scope.INPUT),
                svc.check("伤害自己"));
    }

    @Test
    @DisplayName("多规则并存时取最高 level，且仍按 scope 过滤")
    void highestLevelWinsWithinScope() {
        var svc = serviceWith(
                rule("self_harm", 3, "KEYWORD", "伤害自己", "INPUT"),
                rule("some_l2", 2, "KEYWORD", "难过", "BOTH"));
        // 输出侧：self_harm 不参与 → 只剩 BOTH 的 L2
        assertEquals(2, svc.check("我很难过，甚至伤害自己", GuardrailRuleService.Scope.OUTPUT).level());
        // 输入侧：L3 胜出
        assertEquals(3, svc.check("我很难过，甚至伤害自己", GuardrailRuleService.Scope.INPUT).level());
    }

    @Test
    @DisplayName("空白输入返回 0 级空结果（两侧一致）")
    void blankInputIsClean() {
        var svc = serviceWith(rule("self_harm", 3, "KEYWORD", "伤害自己", "INPUT"));
        assertNull(svc.check("", GuardrailRuleService.Scope.INPUT).ruleId());
        assertNull(svc.check(null, GuardrailRuleService.Scope.OUTPUT).ruleId());
    }
}
