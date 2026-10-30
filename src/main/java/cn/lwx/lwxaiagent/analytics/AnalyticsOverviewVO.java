package cn.lwx.lwxaiagent.analytics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h3>运营总览 VO（PM 看板）</h3>
 *
 * <p>结构（契约已冻结）：</p>
 * <pre>{@code
 * {
 *   "days": 30,
 *   "generatedAt": "2026-10-14T20:00:00",
 *   "excludedTestUsers": 2400,          // 命中排除前缀的用户数（如实上报，不许静默过滤）
 *   "series": [ {"date":"2026-09-15","activeUsers":3,"newUsers":1,"messages":12} ],
 *   "retention": {"cohortSize":50,"d1":0.42,"d7":0.18},
 *   "funnel": [ {"step":"注册","count":50}, ... ],
 *   "features": [ {"name":"聊天会话","count":120} ]
 * }
 * }</pre>
 *
 * <p>⛔ 隐私底线：整个 VO 只出<b>计数</b>，绝不出任何消息正文或用户名。</p>
 */
public record AnalyticsOverviewVO(
        int days,
        LocalDateTime generatedAt,
        long excludedTestUsers,
        DataQualityVO dataQuality,
        List<DailyPointVO> series,
        RetentionVO retention,
        List<FunnelStepVO> funnel,
        List<FeatureUsageVO> features) {

    /** 单日数据点（按天升序，长度 = days，含今天；无数据的天补 0）。 */
    public record DailyPointVO(String date, long activeUsers, long newUsers, long messages) {}

    /** 留存：cohort = 窗口内注册的非测试用户；d1/d7 ∈ [0,1]；无 cohort 时为 0（绝不为 NaN）。 */
    public record RetentionVO(long cohortSize, double d1, double d7) {}

    /** 漏斗步骤（顺序固定：注册 → 首次对话 → 次日回访）。 */
    public record FunnelStepVO(String step, long count) {}

    /** 各功能使用量（降序）。 */
    public record FeatureUsageVO(String name, long count) {}

    /**
     * 数据质量自述（2026-10-13 补）。
     *
     * <p>⛔ <b>为什么必须有它</b>：排除清单是**枚举式**的，而实测本机库里有 <b>193 个不同前缀段</b>
     * 的合成账号 —— 枚举永远追不上。与其假装数字干净，不如把"有多少被排除、比例多高"摆出来，
     * 让看板的人自己判断能不能信。排除比例过高时直接标记 {@code suspect}。</p>
     */
    public record DataQualityVO(
            long totalUsers,
            long excludedUsers,
            /** 被排除占比 ∈ [0,1]；totalUsers=0 时为 0（不是 NaN）。 */
            double excludedRatio,
            /** true = 排除比例 > 0.5，本库很可能被测试数据污染，数字仅供参考。 */
            boolean suspect
    ) {}
}
