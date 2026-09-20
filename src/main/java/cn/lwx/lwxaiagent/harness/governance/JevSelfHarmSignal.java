package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 自伤风险的<b>第二信号</b>（Jev / System One 的 {@code noul} 判定）。
 *
 * <p><b>只加召回，不替兜底</b>——这是本类存在的全部意义，也是它的使用约束：</p>
 * <ul>
 *   <li>词典规则（{@link GuardrailRuleService}）<b>零延迟、确定性、不依赖网络</b>，是唯一兜底。
 *       本信号只能在规则<b>没有</b>判 L3 时"升级"，<b>永远不能</b>放行规则已命中的消息。</li>
 *   <li>不可达/超时/未启用/未越阈值 → 一律不升级，行为与接入前完全一致。
 *       <b>这不是"安全回退"</b>：接入前本来就漏这些口语变体，只是没有变得更差。</li>
 *   <li>阈值 {@code min-probability} 默认 0.6：<b>标定值，不是拍的</b>
 *       （{@code scripts/jev_guardrail_threshold_probe.py}）。</li>
 * </ul>
 *
 * <p><b>判定与决策分离</b>（{@code docs/phase7-jev-shadow}）：{@link #judge(String)} 只回答
 * "概率多少、是否越阈"，拦不拦由调用方按 {@link #mode()} 决定。这样 {@code SHADOW} 模式能拿到
 * <b>全部</b>概率（含低于阈值的部分）——否则分布的主体部分会被阈值吃掉，观测就失去意义。</p>
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

    /**
     * 一次判定的结果。
     *
     * @param probability       Jev 判"有自伤意愿"的概率（0~1）
     * @param exceedsThreshold 是否达到 {@code min-probability}
     */
    public record Risk(double probability, boolean exceedsThreshold) {}

    /** 当前模式。调用方据此决定"拦 / 只记录 / 不管"。 */
    public JevProperties.Mode mode() {
        return props.getGuardrail().getMode();
    }

    /** 当前生效的升级阈值（落库时一并记录，便于日后复算"当时为什么没拦"）。 */
    public double threshold() {
        return props.getGuardrail().getMinProbability();
    }

    /** 是否具备判定条件（模式非 OFF 且客户端可用）。 */
    public boolean enabled() {
        return mode() != JevProperties.Mode.OFF && jev.available();
    }

    /**
     * 判定一条用户消息的自伤风险。
     *
     * <p><b>任何失败都返回 {@link Optional#empty()} 且不抛异常</b>：未启用、客户端不可用、
     * 网络不可达、非 200、响应缺字段。调用方一律按"没升到级"处理。</p>
     *
     * @return 概率与是否越阈；失败时为空。**不做拦截决策**
     */
    public Optional<Risk> judge(String userText) {
        if (!enabled() || userText == null || userText.isBlank()) return Optional.empty();
        var risk = jev.noul("self_harm", QUESTION, TRUE_MEANING, FALSE_MEANING, "user_text", userText);
        if (risk.isEmpty()) return Optional.empty();
        double probability = risk.get();
        boolean exceeds = probability >= props.getGuardrail().getMinProbability();
        if (log.isDebugEnabled()) {
            log.debug("Guardrail jev second signal: p={} exceeds={}", probability, exceeds);
        }
        return Optional.of(new Risk(probability, exceeds));
    }
}
