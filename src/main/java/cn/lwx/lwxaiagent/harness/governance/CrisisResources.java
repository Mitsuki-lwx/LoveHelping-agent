package cn.lwx.lwxaiagent.harness.governance;

/**
 * <h1>危机干预资源（合规：出站文案的**唯一事实源**）</h1>
 *
 * <p>⛔ <b>为什么要有这个类（2026-10-13，ADR-89）</b>：转介文案此前有 <b>4 份副本</b>，
 * 而且已经漂移 —— 两份带两条热线、两份<b>只有一条</b>（{@code ChatEntry} / {@code SandboxController}）。
 * 通用婉拒文案另有 3 份。这意味着<b>改一次热线要动 4~7 个地方</b>，漏一处就有用户在
 * 最危险的时刻拿到一条不完整的信息。热线的有效期是监管意义上的（号码会变），
 * 所以这不是"重复代码不好看"，是<b>合规风险</b>。</p>
 *
 * <p><b>口径刻意分成两条</b>（ADR-55 的既有决策，本次只是把"热线清单"抽出来共享）：</p>
 * <ul>
 *   <li>{@link #referralText()} —— <b>输入侧</b>：用户自己的话触发 → 假定"你本人正在危机中"；</li>
 *   <li>{@link #selfHarmOutputText()} —— <b>输出侧</b>：助手在教唆自伤 → 中性措辞，
 *       因为提问者可能是"想帮朋友的人"，沿用"你现在的状态…"就是答非所问（ADR-55 缺陷 D3）。</li>
 * </ul>
 *
 * <p><b>可运营</b>：热线清单由 {@code app.crisis.hotlines} 覆盖（环境变量
 * {@code APP_CRISIS_HOTLINES}）⇒ 换号<b>不用发版</b>。见 {@link CrisisResourcesConfig}。</p>
 */
public final class CrisisResources {

    private CrisisResources() {}

    /**
     * 默认热线清单（单一事实源）。
     *
     * <p>⛔ 改这里 = 改所有出站文案。运营期请用 {@code app.crisis.hotlines} 覆盖，不要改这个常量。</p>
     */
    static final String DEFAULT_HOTLINES =
            "全国心理援助热线 400-161-9995；北京心理危机研究与干预中心 010-82951332";

    private static volatile String hotlines = DEFAULT_HOTLINES;

    /** 当前生效的热线清单。 */
    public static String hotlines() {
        return hotlines;
    }

    /**
     * 启动时由配置覆盖（见 {@link CrisisResourcesConfig}）。
     *
     * <p>空值/空白一律忽略 —— 配置写错时**宁可退回默认热线，也不能让文案里没有热线**。</p>
     */
    static void overrideHotlines(String value) {
        if (value != null && !value.isBlank()) {
            hotlines = value.trim();
        }
    }

    /** 输入侧转介文案：用户本人的话触发危机。 */
    public static String referralText() {
        return "我注意到你现在的状态可能非常难受。如果你正在经历难以承受的时刻，请一定联系专业援助："
                + hotlines() + "。你不需要独自面对。";
    }

    /** 输出侧自伤替换文案：中性措辞（提问者可能是想帮朋友的人，ADR-55 D3）。 */
    public static String selfHarmOutputText() {
        return "这个话题我需要谨慎处理。如果你或你身边的人正在经历难以承受的时刻，"
                + "请联系专业援助：" + hotlines() + "。你不必独自面对。";
    }

    /**
     * 通用 L3 婉拒文案（输入侧与输出侧**共用**，此前也是 3 份副本）。
     *
     * <p>它不是"危机资源"，所以是常量；但它同样只能有一份 —— 用户看到 A、系统记录 B 是最坏的形态。</p>
     */
    public static final String BLOCK_TEXT =
            "这个话题涉及的内容我不能帮你处理。如果你愿意，我们可以聊聊关系中的沟通、情绪与相处之道。";
}
