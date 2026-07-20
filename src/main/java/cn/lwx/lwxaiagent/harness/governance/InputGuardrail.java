package cn.lwx.lwxaiagent.harness.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class InputGuardrail {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(previous|prior|above|all)\\s+instructions?"),
            Pattern.compile("(?i)disregard\\s+(previous|prior|all)"),
            Pattern.compile("(?i)(you\\s+are\\s+now|act\\s+as|pretend\\s+you\\s+are)\\s+(a|an)\\s+(system|admin|developer)"),
            Pattern.compile("(?i)(reveal|show|display|print)\\s+(your\\s+)?(system\\s+)??(prompt|instructions?|rules?)"),
            Pattern.compile("(?i)忽略(以上|之前|前面|所有)(的)?(指令|提示|规则|设定)"),
            Pattern.compile("(?i)(你现在|请)(是|扮演|充当)(一个)?(系统|管理员|开发者|root)"),
            Pattern.compile("(?i)(说出|显示|打印|泄露)(你的?)?(系统|原始|底层)?(提示|指令|规则|prompt)"),
            Pattern.compile("(?i)DAN\\s+mode|jailbreak|越狱|突破限制")
    );

    private static final List<Pattern> ABUSE_PATTERNS = List.of(
            Pattern.compile("(?i)(fuck|shit|bitch|asshole|dickhead)"),
            Pattern.compile("(傻逼|操你|滚你妈|去死|废物|狗日的)")
    );

    public GuardrailResult check(String input) {
        if (input == null || input.isBlank()) return GuardrailResult.pass();

        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(input).find()) {
                log.warn("Input blocked (injection): {} -> {}", p.pattern(), truncate(input));
                String fallback = "检测到潜在的 Prompt 注入尝试，请正常描述你的情感问题。";
                return GuardrailResult.block("prompt_injection", fallback);
            }
        }

        String abuseHit = null;
        for (Pattern p : ABUSE_PATTERNS) {
            if (p.matcher(input).find()) {
                abuseHit = p.pattern();
                break;
            }
        }
        if (abuseHit != null) {
            log.warn("Input blocked (abuse): {} -> {}", abuseHit, truncate(input));
            String fallback = "请使用文明语言描述你的问题，我会尽力帮助你。";
            return GuardrailResult.block("abusive_language", fallback);
        }

        return GuardrailResult.pass();
    }

    private String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
