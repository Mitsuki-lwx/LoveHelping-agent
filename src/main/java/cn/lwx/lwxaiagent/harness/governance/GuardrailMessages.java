package cn.lwx.lwxaiagent.harness.governance;

/**
 * 护栏出站文案（ADR-55）。
 *
 * <p><b>为什么抽成常量类</b>：这些文案有<b>两个</b>使用方 ——
 * {@code CheckNode}（图末端的事后替换）与 {@code StreamRegistry.StreamSink}（流式出站的实时拦截）。
 * 若各写一份，两条路径迟早会给出不同的话（同一缺陷的另一种形态：
 * 用户看到 A、系统记录 B）。放在这里让它们<b>只有一个事实源</b>。</p>
 *
 * <p>放在 {@code harness.governance} 而不是节点所在包，是为了避免
 * {@code infrastructure.orchestration} ↔ {@code ...graph.node} 的循环包依赖。</p>
 */
public final class GuardrailMessages {

    private GuardrailMessages() {}

    /**
     * 输出侧命中<b>自伤类</b>（{@code self_harm} / {@code self_harm_incite}）时的替换文案。
     *
     * <p>⛔ <b>中性措辞是刻意的</b>：输入侧的 {@link GuardrailAdvisor} 转介文案假定
     * "用户本人正在危机中"（"你现在的状态可能非常难受"），那在输入侧是对的；
     * 但<b>输出侧</b>被拦的场景是"助手在教唆自伤"，此时提问者可能是<b>想帮朋友的人</b> ——
     * 沿用"你现在的状态…"就是答非所问（ADR-55 缺陷 D3，实测复现）。</p>
     */
    public static final String SELF_HARM_OUTPUT =
            "这个话题我需要谨慎处理。如果你或你身边的人正在经历难以承受的时刻，"
                    + "请联系专业援助：全国心理援助热线 400-161-9995，"
                    + "北京心理危机研究与干预中心 010-82951332。你不必独自面对。";

    /** 输出侧命中其它 L3（伤人 / 违法 / 操控）时的婉拒文案 */
    public static final String OTHER_OUTPUT =
            "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";

    /**
     * 输出侧命中<b>有害建议</b>（{@code harmful_advice}，V30 起由规则表管辖）时的文案。
     *
     * <p>⛔ 与 {@link #OTHER_OUTPUT} 分开是刻意的：这类被拦的场景是"助手在教唆暴力/报复/跟踪"，
     * 给一句**指向健康处理方式**的话比通用婉拒更有帮助（且与迁移前的文案逐字一致，行为不变）。</p>
     */
    public static final String HARMFUL_ADVICE =
            "我无法提供此类建议。在亲密关系中，暴力、报复或控制行为都不是解决问题的健康方式，"
                    + "建议双方冷静沟通或寻求专业调解。";

    /** 按 rule_id 选文案：{@code self_harm*} → 自伤类；{@code harmful_advice*} → 有害建议类；其余 → 通用婉拒 */
    public static String forRule(String ruleId) {
        if (ruleId == null) return OTHER_OUTPUT;
        if (ruleId.startsWith("self_harm")) return SELF_HARM_OUTPUT;
        if (ruleId.startsWith("harmful_advice")) return HARMFUL_ADVICE;
        return OTHER_OUTPUT;
    }
}
