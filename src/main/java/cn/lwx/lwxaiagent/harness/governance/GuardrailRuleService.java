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
    private record CompiledRule(String ruleId, int level, Pattern regex, String keyword, Scope scope) {}

    /**
     * 规则适用范围（ADR-55 / V25）。同一套词表服务两个语义完全不同的场景，
     * 必须显式区分，否则输出侧必然误伤（详见 {@link #check(String, Scope)}）。
     */
    public enum Scope {
        /** 用户输入侧（用户原话）。 */
        INPUT,
        /** 助手输出侧（生成内容）。 */
        OUTPUT,
        /** 两侧都适用（默认值 —— 既有规则全部是这个，保证加字段不改变行为）。 */
        BOTH
    }

    /** DB 里的 scope 文本 → 枚举；非法/缺失一律落 BOTH（不为一条脏数据打断启动） */
    private static Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) return Scope.BOTH;
        try {
            return Scope.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("guardrail_rule.scope 取值非法：{} → 按 BOTH 处理", raw);
            return Scope.BOTH;
        }
    }

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
            return new CompiledRule(r.getRuleId(), r.getLevel(), regex, keyword, parseScope(r.getScope()));
        }).toList();
        long in = rules.stream().filter(r -> r.scope() == Scope.INPUT).count();
        long out = rules.stream().filter(r -> r.scope() == Scope.OUTPUT).count();
        log.info("Guardrail rules loaded: {} enabled (INPUT-only={}, OUTPUT-only={}, BOTH={})",
                rules.size(), in, out, rules.size() - in - out);
    }

    /** 判定结果：0=通过；>0 为命中的最高等级与规则 */
    public record Verdict(int level, String ruleId) {}

    /**
     * 检查（**默认按输入侧**），返回最高命中级。
     *
     * <p>⚠️ 这是一个**兼容重载**：生产调用点应当显式传 {@link Scope}。
     * 保留它是因为三处输入侧调用（{@code GuardrailAdvisor} / {@code ChatEntry} / {@code SandboxController}）
     * 语义一致、不必改 —— 少改一处就少一个回归面。但**默认值是 INPUT 这件事必须记住**：
     * 若将来有人在**输出侧**图省事调了这个无参版本，等于把输入侧词表套到输出上（= 本 ADR 修的那个缺陷）。</p>
     */
    public Verdict check(String text) {
        return check(text, Scope.INPUT);
    }

    /**
     * 按适用范围检查，返回命中最高等级与规则。
     *
     * <p><b>为什么要有 scope 参数</b>（ADR-55）：同一个词在输入侧与输出侧的含义完全不同 ——
     * 输入侧「伤害自己」= 用户有风险；输出侧「你最近有没有想过伤害自己？」= **专业危机干预指导**。
     * 用一套裸关键词表同时服务两侧，输出侧必然误伤（实测：3 组「如何帮助低落的朋友」的正常求助
     * **6/6 轮**被判 L3）。</p>
     *
     * @param scope 判定场景；只取 {@code BOTH} 与本侧规则
     */
    public Verdict check(String text, Scope scope) {
        if (text == null || text.isBlank()) {
            return new Verdict(0, null);
        }
        int maxLevel = 0;
        String hitRule = null;
        for (CompiledRule r : rules) {
            if (r.scope() != Scope.BOTH && r.scope() != scope) continue;
            boolean hit;
            if (r.regex() != null) {
                hit = r.regex().matcher(text).find();
            } else {
                hit = r.keyword() != null && text.contains(r.keyword());
            }
            if (hit && r.level() > maxLevel) {
                maxLevel = r.level();
                hitRule = r.ruleId();
            }
        }
        return new Verdict(maxLevel, hitRule);
    }

    /**
     * 仅按情绪刹车片规则（emotion_brake_*，FR-CORE-02）判定是否命中。
     * 与 {@link #check} 解耦：同一极端词可能同时命中同级 abuse 规则，
     * check 只保留第一条例号（V4 先插），导致 ruleId 前缀过滤失效——刹车片判定直接问"是否含刹车词"。
     */
    public boolean matchesEmotionBrake(String input) {
        if (input == null || input.isBlank()) return false;
        for (CompiledRule r : rules) {
            if (r.ruleId() == null || !r.ruleId().startsWith("emotion_brake_")) continue;
            boolean hit = r.regex() != null
                    ? r.regex().matcher(input).find()
                    : r.keyword() != null && input.contains(r.keyword());
            if (hit) return true;
        }
        return false;
    }

    public int ruleCount() {
        return rules.size();
    }
}
