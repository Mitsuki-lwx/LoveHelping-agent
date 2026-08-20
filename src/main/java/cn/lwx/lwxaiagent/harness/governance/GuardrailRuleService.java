package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.entity.GuardrailRule;
import cn.lwx.lwxaiagent.mapper.GuardrailRuleMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 护栏规则服务（ADR-6）：规则外置 DB，启动加载缓存，按命中最高等级判定。
 * <p>
 * 判定：KEYWORD 规则用包含匹配，REGEX 规则用正则 find；多规则命中取最高 level。
 * 规则缓存启动时加载（改动后重启生效；热更新为后续增强）。
 * </p>
 */
@Slf4j
@Component
public class GuardrailRuleService {

    private final GuardrailRuleMapper ruleMapper;
    private volatile List<CompiledRule> rules = List.of();

    public GuardrailRuleService(GuardrailRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 预编译规则（避免每次请求编译正则） */
    private record CompiledRule(String ruleId, int level, Pattern regex, String keyword) {}

    @PostConstruct
    void load() {
        List<GuardrailRule> enabled = ruleMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GuardrailRule>()
                        .eq(GuardrailRule::getEnabled, true));
        rules = enabled.stream().map(r -> {
            Pattern regex = "REGEX".equalsIgnoreCase(r.getPatternType())
                    ? Pattern.compile(r.getPattern()) : null;
            String keyword = "KEYWORD".equalsIgnoreCase(r.getPatternType())
                    ? r.getPattern() : null;
            return new CompiledRule(r.getRuleId(), r.getLevel(), regex, keyword);
        }).toList();
        log.info("Guardrail rules loaded: {} enabled", rules.size());
    }

    /** 判定结果：0=通过；>0 为命中的最高等级与规则 */
    public record Verdict(int level, String ruleId) {}

    /**
     * 输入检查，返回最高命中级。
     */
    public Verdict check(String input) {
        if (input == null || input.isBlank()) {
            return new Verdict(0, null);
        }
        int maxLevel = 0;
        String hitRule = null;
        for (CompiledRule r : rules) {
            boolean hit;
            if (r.regex() != null) {
                hit = r.regex().matcher(input).find();
            } else {
                hit = r.keyword() != null && input.contains(r.keyword());
            }
            if (hit && r.level() > maxLevel) {
                maxLevel = r.level();
                hitRule = r.ruleId();
            }
        }
        return new Verdict(maxLevel, hitRule);
    }

    public int ruleCount() {
        return rules.size();
    }
}
