package cn.lwx.lwxaiagent.harness.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class OutputGuardrail {

    private static final List<String> SELF_HARM_KEYWORDS = List.of(
            "自杀", "自残", "了结自己", "不想活", "了此残生", "寻短见",
            "结束生命", "轻生", "割腕", "跳楼", "吃药自杀"
    );

    private static final List<String> HARMFUL_ADVICE_KEYWORDS = List.of(
            "你应该打", "动手教训", "报复他", "以牙还牙",
            "跟踪她", "查他手机", "控制对方"
    );

    private static final String CRISIS_FALLBACK = """
            如果你正在经历痛苦，请记住你不是一个人。
            🆘 24小时心理援助热线：400-161-9995
            🆘 北京心理危机研究与干预中心：010-82951332
            请寻求专业心理咨询师的帮助，他们能提供更系统的支持。
            """;

    public GuardrailResult check(String output, String userInput) {
        if (output == null || output.isBlank()) return GuardrailResult.pass();

        String userContext = userInput != null ? userInput : "";

        boolean userMentionsSelfHarm = SELF_HARM_KEYWORDS.stream()
                .anyMatch(userContext::contains);
        boolean aiMissingCrisisResponse = userMentionsSelfHarm
                && !output.contains("热线") && !output.contains("专业")
                && !output.contains("求助") && !output.contains("心理");

        if (aiMissingCrisisResponse) {
            log.warn("Output guardrail: user mentioned self-harm but AI missed crisis response");
            return GuardrailResult.block("missing_crisis_response", CRISIS_FALLBACK);
        }

        for (String kw : HARMFUL_ADVICE_KEYWORDS) {
            if (output.contains(kw)) {
                log.warn("Output guardrail: harmful advice detected: '{}'", kw);
                String fallback = "我无法提供此类建议。在亲密关系中，暴力、报复或控制行为都不是解决问题的健康方式，建议双方冷静沟通或寻求专业调解。";
                return GuardrailResult.block("harmful_advice", fallback);
            }
        }

        return GuardrailResult.pass();
    }
}
