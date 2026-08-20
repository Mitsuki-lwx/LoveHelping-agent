package cn.lwx.lwxaiagent.memory;

/**
 * 记忆内容脱敏工具（ADR-14 红线：记忆只存脱敏事实）。
 * <p>覆盖常见 PII：手机号、连续长数字；姓名类无法用规则全覆盖，
 * 已在萃取 Prompt 中约束 LLM 用"对象/对方"替代，此处作二次保险。</p>
 */
public final class Desensitizer {

    private Desensitizer() {}

    /** 中国大陆手机号 → 1*** 后 4 位 */
    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("1[3-9]\\d{9}");

    /** 连续 6 位以上数字（身份证/卡号等）→ 打码 */
    private static final java.util.regex.Pattern LONG_DIGITS =
            java.util.regex.Pattern.compile("\\d{6,}");

    /**
     * 对事实文本做 PII 脱敏。
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String s = PHONE.matcher(text).replaceAll(m -> "1***" + m.group().substring(7));
        s = LONG_DIGITS.matcher(s).replaceAll("***");
        return s.trim();
    }
}
