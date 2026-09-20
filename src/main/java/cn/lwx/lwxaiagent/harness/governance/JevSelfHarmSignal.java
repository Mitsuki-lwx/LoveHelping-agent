package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自伤风险的<b>第二信号</b>（Jev / System One 的 {@code noul} 判定）。
 *
 * <p><b>只加召回，不替兜底</b>——这是本类存在的全部意义，也是它的使用约束：</p>
 * <ul>
 *   <li>词典规则（{@link GuardrailRuleService}）<b>零延迟、确定性、不依赖网络</b>，是唯一兜底。
 *       本信号只能在规则<b>没有</b>判 L3 时"升级"，<b>永远不能</b>放行规则已命中的消息。</li>
 *   <li>不可达/超时/未启用/未越阈值 → 一律返回 {@code false}，行为与接入前完全一致。
 *       <b>这不是"安全回退"</b>：接入前本来就漏这些口语变体，只是没有变得更差。</li>
 *   <li>阈值默认 0.9（高位）：误报会让用户拿不到答案，宁可少召回也不错拦。</li>
 * </ul>
 *
 * <p>探针证据（{@code docs/phase7-jev-routing/spec.md} §S4）：5 例口语化自伤表达 5/5 命中、
 * 21 例负向 0 误报。</p>
 */
@Slf4j
@Component
public class JevSelfHarmSignal {

    /** 官方口径：noul = 干净的是/否，概率本身即信号。 */
    private static final String QUESTION = """
            判断 `user_text` 是否表达了自伤、自杀或"不想继续活下去"的意愿。
            口语化、隐晦、玩笑式的说法也算；纯粹的夸张修辞（如"累死了""游戏里死了"）不算。""";
    private static final String TRUE_MEANING = "有自伤/自杀意愿，或绝望到不想活下去的表达";
    private static final String FALSE_MEANING = "没有这类表达，或只是抱怨劳累、夸张修辞";

    /** 升级时记账用的 ruleId —— 便于事后统计"词典漏、Jev 补"的量。 */
    public static final String RULE_ID = "jev:self_harm";

    private final JevClient jev;
    private final JevProperties props;

    public JevSelfHarmSignal(JevClient jev, JevProperties props) {
        this.jev = jev;
        this.props = props;
    }

    public boolean enabled() {
        return props.getGuardrail().isEnabled() && jev.available();
    }

    /**
     * @return 是否应升级为自伤处理；任何失败（未启用/超时/非 200/缺字段/低于阈值）都返回 {@code false}
     */
    public boolean flagged(String userText) {
        if (!enabled()) return false;
        var risk = jev.noul("self_harm", QUESTION, TRUE_MEANING, FALSE_MEANING, "user_text", userText);
        if (risk.isEmpty()) return false;
        double probability = risk.get();
        if (probability < props.getGuardrail().getMinProbability()) {
            log.debug("Guardrail jev second signal below threshold: p={}", probability);
            return false;
        }
        log.info("Guardrail jev second signal fired: p={}", probability);
        return true;
    }
}
