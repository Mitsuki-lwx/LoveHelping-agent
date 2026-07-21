package cn.lwx.lwxaiagent.harness.governance;

public record GuardrailResult(boolean blocked, String reason, String fallback, boolean vague) {

    public static GuardrailResult pass() {
        return new GuardrailResult(false, null, null, false);
    }

    public static GuardrailResult block(String reason, String fallback) {
        return new GuardrailResult(true, reason, fallback, false);
    }

    public static GuardrailResult vagueHint(String fallback) {
        return new GuardrailResult(true, "vague_input", fallback, true);
    }
}
