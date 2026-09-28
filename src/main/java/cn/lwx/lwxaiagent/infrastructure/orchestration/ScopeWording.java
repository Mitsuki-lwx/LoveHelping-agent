package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 范围护栏（Scope）措辞版本选择器（ADR-53 / phase21）。
 *
 * <p><b>为什么需要它</b>：{@code ChatExecutor.SYSTEM_PROMPT} 的 scope 段原本是一个
 * 写死在拼接常量里的字符串。它在 {@code 5d8c4c3}（ADR-48）被改成 A/B 两类边界
 * （情感相邻议题先帮再接回 / 纯事务性请求才拒），但那次改动<b>从未做过对照验证</b>。
 * ADR-48 原文要求「改完必须用同一套两臂对照复验」，而原对照是
 * {@code space-bunny-alpha} vs {@code glm-4-flash} <b>两个模型</b>——
 * ADR-51 把主链切成 DeepSeek 官方、原 B 臂 bigmodel 实测 400 之后，
 * <b>那套对照已不可复现</b>。
 *
 * <p><b>不设开关的致命歧义</b>：若只跑新措辞看到 0 拒答，<b>分不清</b>
 * 「措辞修复有效」与「deepseek-flash 本来就不怎么拒答」。
 * 这正是 ADR-47 第一版结论（"160 vs 726 = A 臂能力更差"）翻车的形状 ——
 * 拿一个相关数字当因果。故必须能在<b>同 provider、只差措辞</b>的条件下做单变量对照。
 *
 * <p><b>为什么用静态生效值而不是构造参数注入</b>：{@code SYSTEM_PROMPT} 有
 * <b>4 处</b>读取路径（{@code ChatClient.defaultSystem}、{@code ChatExecutor.issuePrompt}、
 * {@code AgentRegistry} 的 general agent、{@code AgentLlmNode.systemPrompt()}），
 * 后两处是<b>静态/PostConstruct</b> 上下文。若只改其中一处构造器，
 * 对照臂会<b>部分路径没切</b> —— 那种"以为切了其实没切"的无痕失效最难查。
 * 启动期一次性装配 + 四处统一读 {@link #activeSystemPrompt()}，从结构上排除漏改。
 *
 * <p><b>取值非法时启动失败</b>（不静默回退默认）：本仓已踩过
 * 「profile yml 字面值优先于环境变量 → 我 export 了 ≠ 它生效了」。
 * 一个被静默忽略的开关会让对照臂失效且无痕。
 */
@Component
public class ScopeWording {

    /** 生产默认：情感相邻议题先帮再接回；纯事务性请求才礼貌拒绝。 */
    public static final String ADJACENT_HELP = "adjacent-help";
    /** 修复前措辞：一律对"明显无关请求"礼貌拒绝 —— 会无谓拒答，<b>仅作对照臂</b>。 */
    public static final String STRICT = "strict";

    private static volatile String active = ADJACENT_HELP;

    public ScopeWording(@Value("${app.chat.scope-wording:" + ADJACENT_HELP + "}") String wording) {
        if (!ADJACENT_HELP.equals(wording) && !STRICT.equals(wording)) {
            throw new IllegalStateException(
                    "app.chat.scope-wording 取值非法：" + wording
                            + "；可选 " + ADJACENT_HELP + "（生产默认）| " + STRICT + "（对照臂，线上不建议启用）");
        }
        active = wording;
        // 对照臂"真的切了"必须有可查询证据 —— 不能靠"我 export 了"（本仓纪律：
        // profile yml 字面值优先于环境变量，"我 export 了" ≠ "它生效了"）。
        org.slf4j.LoggerFactory.getLogger(ScopeWording.class).info(describe());
    }

    /**
     * 当前生效的完整 system prompt（按 scope 措辞版本组装）。
     *
     * <p>供 {@code ChatExecutor} / {@code AgentRegistry} / {@code AgentLlmNode}
     * 三条路径统一读取，避免"只改一处、其余仍用旧措辞"。
     */
    public static String activeSystemPrompt() {
        return activeSystemPrompt(active);
    }

    static String activeSystemPrompt(String wording) {
        return switch (wording) {
            case STRICT -> ChatExecutor.SYSTEM_PROMPT_HEAD + ChatExecutor.SCOPE_STRICT + ChatExecutor.SYSTEM_PROMPT_TAIL;
            case ADJACENT_HELP ->
                    ChatExecutor.SYSTEM_PROMPT_HEAD + ChatExecutor.SCOPE_ADJACENT_HELP + ChatExecutor.SYSTEM_PROMPT_TAIL;
            default -> throw new IllegalStateException("app.chat.scope-wording 取值非法：" + wording);
        };
    }

    /** 供装置 grep 的自报行 —— 对照臂"真的切了"必须有可查询证据，不能靠读代码推断。 */
    public static String describe() {
        return STRICT.equals(active)
                ? "[scope-wording] 生效范围护栏措辞 = strict（⚠️ 对照臂：会无谓拒答，线上不建议启用）"
                : "[scope-wording] 生效范围护栏措辞 = adjacent-help（生产默认）";
    }

    static String current() {
        return active;
    }
}
