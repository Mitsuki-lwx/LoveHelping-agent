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

    private static final List<Pattern> VAGUE_PATTERNS = List.of(
            Pattern.compile("^[^，。？\\?]{1,15}$"),
            Pattern.compile("(?i)^(怎么|如何|怎样|咋|该怎么办|怎么办|帮帮我|求救|急)(追|撩|哄|挽回|表白|相处|分手|吵架|生气|冷淡)"),
            Pattern.compile("(?i)^(她|他|对方)(生气|不理我|冷淡|出轨|提分手|说分手)"),
            Pattern.compile("(?i)^(吵架了|分手了|闹矛盾|冷战|有矛盾|出问题了)"),
            Pattern.compile("(?i)^(喜欢一个人|爱上|暗恋|想追|在一起)怎么办")
    );

    public GuardrailResult check(String input) {
        if (input == null || input.isBlank()) return GuardrailResult.pass();

        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(input).find()) {
                log.warn("Input blocked (injection): {} -> {}", p.pattern(), truncate(input));
                return GuardrailResult.block("prompt_injection",
                        "检测到潜在的 Prompt 注入尝试，请正常描述你的情感问题。");
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
            return GuardrailResult.block("abusive_language",
                    "请使用文明语言描述你的问题，我会尽力帮助你。");
        }

        for (Pattern p : VAGUE_PATTERNS) {
            if (p.matcher(input.trim()).find()) {
                log.info("Input vague: {} matched pattern {}", truncate(input), p.pattern());
                return GuardrailResult.vagueHint(
                        "你的描述比较简略，能多说说具体情况吗？比如：\n" +
                        "• 你们在一起多久了？\n" +
                        "• 具体发生了什么事？\n" +
                        "• 你尝试过什么方式沟通？\n" +
                        "• 对方有什么反应？\n\n" +
                        "告诉我更多细节，我才能给你更有针对性的建议 😊");
            }
        }

        return GuardrailResult.pass();
    }

    private String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
