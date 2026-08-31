package cn.lwx.lwxaiagent.infrastructure.orchestration.graph.node;

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

    private final GuardrailRuleService guardrailRuleService;

    public CheckNode(GuardrailRuleService guardrailRuleService) {
        this.guardrailRuleService = guardrailRuleService;
    }

    public Map<String, Object> apply(OverAllState state) {
        String output = state.value(GraphStateKeys.OUTPUT).map(Object::toString).orElse("");
        if (output.isBlank()) {
            return Map.of();
        }
        Verdict v = guardrailRuleService.check(output);
        if (v.level() >= 3) {
            log.warn("Final-reply guardrail L3 blocked ({}): {}", v.ruleId(), output.length() > 40 ? output.substring(0, 40) : output);
            String replaced = "self_harm".equals(v.ruleId())
                    ? "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助：全国心理援助热线 400-161-9995，北京心理危机研究与干预中心 010-82951332。你不需要独自面对，我们慢慢聊。"
                    : "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
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
}