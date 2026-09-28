package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

import cn.lwx.lwxaiagent.harness.governance.GuardrailMessages;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.GraphStateKeys;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService.Verdict;
import com.alibaba.cloud.ai.graph.OverAllState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一检查节点（ADR-19 CAP-5）：对最终回复做护栏复检与降级。
 * 生成内容出现 L3 自伤 → 转介文案；L3 其他（伤人/违法/操控）→ 婉拒文案；
 * L1/L2 → 保留并记录。单点出口便于后续追加检查项（话术激活标记等）。
 */
@Slf4j
@Component
public class CheckNode {

    /**
     * 输出侧自伤拦截的替换文案（ADR-55）。
     *
     * <p>⚠️ 唯一的文案事实源在 {@link GuardrailMessages} —— {@code StreamRegistry} 的流式拦截
     * 也用同一份，两条路径必须给出一致的话。</p>
     */
    public static final String OUTPUT_SELF_HARM_REPLACEMENT = GuardrailMessages.SELF_HARM_OUTPUT;

    /** 输出侧其它 L3（伤人/违法/操控）的婉拒文案 */
    public static final String OUTPUT_OTHER_REPLACEMENT = GuardrailMessages.OTHER_OUTPUT;

    private final GuardrailRuleService guardrailRuleService;

    public CheckNode(GuardrailRuleService guardrailRuleService) {
        this.guardrailRuleService = guardrailRuleService;
    }

    public Map<String, Object> apply(OverAllState state) {
        String output = state.value(GraphStateKeys.OUTPUT).map(Object::toString).orElse("");
        if (output.isBlank()) {
            return Map.of();
        }
        // ADR-55：**必须显式传 OUTPUT** —— 输入侧与输出侧共用一套词表时，
        // 输出侧会把"专业地提到自伤"当成"危险内容"（实测 6/6 轮正常求助被误判）。
        Verdict v = guardrailRuleService.check(output, GuardrailRuleService.Scope.OUTPUT);
        if (v.level() >= 3) {
            log.warn("Final-reply guardrail L3 blocked ({}): {}", v.ruleId(), output.length() > 40 ? output.substring(0, 40) : output);
            String replaced = isSelfHarm(v.ruleId()) ? OUTPUT_SELF_HARM_REPLACEMENT : OUTPUT_OTHER_REPLACEMENT;
            Map<String, Object> out = new HashMap<>();
            out.put(GraphStateKeys.OUTPUT, replaced);
            return out;
        }
        if (v.level() > 0) {
            log.info("Final-reply guardrail L{} logged ({}): {}", v.level(), v.ruleId(),
                    output.length() > 40 ? output.substring(0, 40) : output);
        }
        return Map.of();
    }

    /** {@code self_harm}（输入侧裸词）/ {@code self_harm_incite}（输出侧教唆）都算自伤类 */
    static boolean isSelfHarm(String ruleId) {
        return ruleId != null && ruleId.startsWith("self_harm");
    }
}