package cn.lwx.lwxaiagent.harness.governance;

public record GuardrailResult(boolean blocked, String reason, String fallback) {
    public static GuardrailResult pass() { return new GuardrailResult(false, null, null); }
    public static GuardrailResult block(String reason) { return new GuardrailResult(true, reason, null); }
    public static GuardrailResult block(String reason, String fallback) {
        return new GuardrailResult(true, reason, fallback);
    }
}
