package cn.lwx.lwxaiagent.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h3>分析层服务（PM 的眼睛）</h3>
 *
 * <p>口径（全部冻结，勿自创）：</p>
 * <ul>
 *   <li>时区一律 {@code Asia/Shanghai}——库里是 timestamp，按东八区取 DATE；</li>
 *   <li>测试账号：{@code users.username} 命中
 *       {@link AnalyticsProperties#getExcludeUsernamePrefixes()} 任一前缀即排除，
 *       且被排除数必须如实上报（{@code excludedTestUsers}）；</li>
 *   <li>activeUsers(day)：该日有 message 的非测试、非 anonymous 用户去重数；</li>
 *   <li>messages(day)：该日 message 行数（非测试 + 非 anonymous + deleted=0）；</li>
 *   <li>newUsers(day)：该日注册的非测试用户数；</li>
 *   <li>retention.d1：窗口内注册的非测试用户中，注册次日有 message 的比例；无 cohort = 0（不是 NaN）；</li>
 *   <li>retention.d7：注册后 7 日内（含第 7 日）有 message 的比例；</li>
 *   <li>funnel：注册 → 首次对话（≥1 条 message）→ 次日回访（d1 的绝对人数）；</li>
 *   <li>features：六个功能的使用量（契约固定，降序）。</li>
 * </ul>
 *
 * <p>⛔ 查询必须有界：days ∈ [1, 90]，所有明细查询都带 {@code created_at >= ?} 下界。</p>
 */
@Service
public class AnalyticsService {

    /** days 上限（防止无界全表扫）。 */
    public static final int MAX_DAYS = 90;
    /** days 下限。 */
    public static final int MIN_DAYS = 1;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final AnalyticsProperties properties;

    public AnalyticsService(JdbcTemplate jdbc, AnalyticsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 组装总览。days 越界夹到 [1,90]；series 按天补齐（没有数据的天 = 0）。 */
    public AnalyticsOverviewVO overview(int days) {
        int clamped = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = today.minusDays(clamped - 1L);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay(ZONE).toLocalDateTime());

        long excludedTestUsers = countExcludedTestUsers();
        Map<LocalDate, long[]> daily = dailySeries(start, clamped);
        AnalyticsOverviewVO.RetentionVO retention = retention(start, clamped);
        List<AnalyticsOverviewVO.FunnelStepVO> funnel = funnel(from, today);
        List<AnalyticsOverviewVO.FeatureUsageVO> features = features(from);

        List<AnalyticsOverviewVO.DailyPointVO> series = new ArrayList<>(clamped);
        for (int i = 0; i < clamped; i++) {
            LocalDate d = start.plusDays(i);
            long[] v = daily.getOrDefault(d, new long[3]);
            series.add(new AnalyticsOverviewVO.DailyPointVO(d.toString(), v[0], v[1], v[2]));
        }

        // 数据质量自述：排除清单是枚举式的，本机实测有 193 个不同前缀段的合成账号 ——
        // 枚举追不上，所以把"排除了多少、比例多高"摆出来让看板的人自己判断。
        long totalUsers = countTotalUsers();
        double excludedRatio = totalUsers == 0 ? 0.0 : (double) excludedTestUsers / totalUsers;
        AnalyticsOverviewVO.DataQualityVO quality = new AnalyticsOverviewVO.DataQualityVO(
                totalUsers, excludedTestUsers, excludedRatio, excludedRatio > 0.5);

        return new AnalyticsOverviewVO(clamped, LocalDateTime.now(ZONE),
                excludedTestUsers, quality, series, retention, funnel, features);
    }

    /**
     * 命中排除前缀的用户数 = **总数 − 非测试数**。
     *
     * <p>⛔ 2026-10-14 修正：此前直接 `COUNT(*) WHERE notTestUser(username)` —— 那是
     * <b>非测试用户</b>（留下的），却当"已排除"上报，**语义整个反了**：
     * 排除清单越长、这个数越小，与"已排除"的直觉相反（实测：清单变长后 559 → 127）。</p>
     */
    long countExcludedTestUsers() {
        Object[] args = prefixArgs();
        Long kept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE " + notTestUser("username"),
                Long.class, args);
        return Math.max(0L, countTotalUsers() - (kept == null ? 0L : kept));
    }

    /** 用户总数（数据质量分母）。 */
    /** 用户总数（数据质量分母）。 */
    long countTotalUsers() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) AS total_users FROM users", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * 按日聚合：SELECT 返回 (DATE, activeUsers, newUsers, messages)。
     * <ul>
     *   <li>activeUsers：该日有 message 的去重 user_id，排除测试前缀、排除 anonymous；</li>
     *   <li>messages：该日 message 行数（非测试 + 非 anonymous + deleted=0）；</li>
     *   <li>newUsers：该日注册的非测试用户数（users.created_at 落在窗口内）。</li>
     * </ul>
     */
    Map<LocalDate, long[]> dailySeries(LocalDate start, int days) {
        LocalDate end = start.plusDays(days - 1L);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay(ZONE).toLocalDateTime());
        Timestamp to = Timestamp.valueOf(end.plusDays(1).atStartOfDay(ZONE).toLocalDateTime());
        Object[] args = prefixArgs();

        // 活跃用户 + 消息数：同一张 message 表，一次扫出（有界：created_at BETWEEN）。
        // ⛔ 2026-10-13 实测踩过两次，都是这条 SQL：
        //   ① 先写成 `CASE WHEN <notTestUser> THEN ...` 放在 **SELECT 列表**里 ——
        //      占位符在文本里的顺序就变成「前缀×2 → 时间戳」，而代码按「时间戳 → 前缀」绑
        //      ⇒ 运行期 `Incorrect TIMESTAMP value: 'sent_%'`（**个数对、顺序错**）。
        //   ② 那层 CASE 本来就是多余的：过滤条件放进 WHERE 一样成立，而且**顺序天然正确**。
        //   ⇒ 教训：**别让占位符顺序依赖读者去数文本**，能挪进 WHERE 就别放 SELECT。
        String dailySql =
                "SELECT DATE(CONVERT_TZ(m.created_at, @@session.time_zone, '+08:00')) AS d, "
              + "COUNT(DISTINCT m.user_id) AS active_users, COUNT(*) AS msg_cnt "
              + "FROM message m "
              + "WHERE m.deleted = 0 AND m.user_id <> 'anonymous' "
              + "AND m.created_at >= ? AND m.created_at < ? "
              + "AND " + notTestUser("m.user_id") + " "
              + "GROUP BY d";
        // 顺序必须与 SQL 文本里的 ? 一致：先两个时间戳（WHERE 开头），再前缀（WHERE 末尾）
        Object[] dailyArgs = concat(new Object[]{from, to}, args);
        Map<LocalDate, long[]> result = new HashMap<>();
        jdbc.query(dailySql, rs -> {
            LocalDate d = rs.getDate("d").toLocalDate();
            long active = rs.getLong("active_users");
            long msgs = rs.getLong("msg_cnt");
            result.put(d, new long[]{active, 0, msgs});
        }, dailyArgs);

        // 注册数：users.created_at 落在窗口内、非测试用户。
        String regSql =
                "SELECT DATE(CONVERT_TZ(u.created_at, @@session.time_zone, '+08:00')) AS d, COUNT(*) AS c "
              + "FROM users u WHERE u.created_at >= ? AND u.created_at < ? AND "
              + notTestUser("u.username") + " GROUP BY d";
        jdbc.query(regSql, rs -> {
            LocalDate d = rs.getDate("d").toLocalDate();
            long[] v = result.get(d);
            if (v == null) {
                v = new long[3];
                result.put(d, v);
            }
            v[1] = rs.getLong("c");
        // ⛔ 必须 concat：regSql 里 notTestUser("u.username") 展开出 20 个 ?，
        //    只传 from/to 会运行期报 `No value specified for parameter 3`（机械闸门实测抓到）
        }, concat(new Object[]{from, to}, args));

        return result;
    }

    /** 留存（d1/d7 ∈ [0,1]；无 cohort 返回 0，绝不返回 NaN）。 */
    AnalyticsOverviewVO.RetentionVO retention(LocalDate start, int days) {
        LocalDate end = start.plusDays(days - 1L);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay(ZONE).toLocalDateTime());
        Timestamp to = Timestamp.valueOf(end.plusDays(1).atStartOfDay(ZONE).toLocalDateTime());
        Object[] args = prefixArgs();

        // cohort：窗口内注册的非测试用户（c0 = 注册数，c1 = 注册次日有 message，c7 = 7 日内有 message）。
        String sql =
                "SELECT COUNT(*) AS c0, "
              + "COUNT(DISTINCT CASE WHEN EXISTS (SELECT 1 FROM message m2 WHERE m2.user_id = u.username "
              + "  AND m2.deleted = 0 AND m2.created_at >= DATE_ADD(u.created_at, INTERVAL 1 DAY) "
              + "  AND m2.created_at <  DATE_ADD(u.created_at, INTERVAL 2 DAY)) THEN u.username END) AS c1, "
              + "COUNT(DISTINCT CASE WHEN EXISTS (SELECT 1 FROM message m3 WHERE m3.user_id = u.username "
              + "  AND m3.deleted = 0 AND m3.created_at >= u.created_at "
              + "  AND m3.created_at <  DATE_ADD(u.created_at, INTERVAL 8 DAY)) THEN u.username END) AS c7 "
              + "FROM users u WHERE u.created_at >= ? AND u.created_at < ? AND "
              + notTestUser("u.username");
        long[] c = new long[3];
        jdbc.query(sql, rs -> {
            c[0] = rs.getLong("c0");
            c[1] = rs.getLong("c1");
            c[2] = rs.getLong("c7");
        }, concat(new Object[]{from, to}, args));
        double d1 = c[0] == 0 ? 0.0 : (double) c[1] / c[0];
        double d7 = c[0] == 0 ? 0.0 : (double) c[2] / c[0];
        return new AnalyticsOverviewVO.RetentionVO(c[0], d1, d7);
    }

    /** 漏斗（顺序固定）：注册 → 首次对话 → 次日回访（d1 的绝对人数）。 */
    List<AnalyticsOverviewVO.FunnelStepVO> funnel(Timestamp from, LocalDate today) {
        Timestamp to = Timestamp.valueOf(today.plusDays(1).atStartOfDay(ZONE).toLocalDateTime());
        Object[] args = prefixArgs();

        Long registered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ? AND "
                        + notTestUser("u.username"),
                Long.class, concat(new Object[]{from, to}, args));
        Long firstChat = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ? "
              + "AND EXISTS (SELECT 1 FROM message m WHERE m.user_id = u.username AND m.deleted = 0) AND "
              + notTestUser("u.username"),
                Long.class, concat(new Object[]{from, to}, args));
        Long nextDay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u WHERE u.created_at >= ? AND u.created_at < ? "
              + "AND EXISTS (SELECT 1 FROM message m WHERE m.user_id = u.username AND m.deleted = 0 "
              + "  AND m.created_at >= DATE_ADD(u.created_at, INTERVAL 1 DAY) "
              + "  AND m.created_at <  DATE_ADD(u.created_at, INTERVAL 2 DAY)) AND "
              + notTestUser("u.username"),
                Long.class, concat(new Object[]{from, to}, args));

        List<AnalyticsOverviewVO.FunnelStepVO> funnel = new ArrayList<>(3);
        funnel.add(new AnalyticsOverviewVO.FunnelStepVO("注册", nz(registered)));
        funnel.add(new AnalyticsOverviewVO.FunnelStepVO("首次对话", nz(firstChat)));
        funnel.add(new AnalyticsOverviewVO.FunnelStepVO("次日回访", nz(nextDay)));
        return funnel;
    }

    /** 功能使用量（降序）。所有计数都排除测试用户；口径与契约一致。 */
    List<AnalyticsOverviewVO.FeatureUsageVO> features(Timestamp from) {
        Object[] args = prefixArgs();
        List<AnalyticsOverviewVO.FeatureUsageVO> features = new ArrayList<>(6);

        Long chatSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_conversations uc JOIN users u ON u.username = uc.user_id "
              + "WHERE uc.chat_type = 'love' AND " + notTestUser("u.username"),
                Long.class, args);
        Long sandboxSessions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sandbox_session ss JOIN users u ON u.username = ss.user_id "
              + "WHERE " + notTestUser("u.username"),
                Long.class, args);
        Long taView = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log al WHERE al.action = 'sandbox_ta_view' "
              + "AND " + notTestUser("al.actor"),
                Long.class, args);
        Long actionItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM action_item ai JOIN users u ON u.username = ai.user_id "
              + "WHERE " + notTestUser("u.username"),
                Long.class, args);
        Long sentimentLogs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sentiment_log sl JOIN users u ON u.username = sl.user_id "
              + "WHERE " + notTestUser("u.username"),
                Long.class, args);
        Long complaints = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complaint cp JOIN users u ON u.username = cp.user_id "
              + "WHERE " + notTestUser("u.username"),
                Long.class, args);

        features.add(new AnalyticsOverviewVO.FeatureUsageVO("聊天会话", nz(chatSessions)));
        features.add(new AnalyticsOverviewVO.FeatureUsageVO("沙盘会话", nz(sandboxSessions)));
        features.add(new AnalyticsOverviewVO.FeatureUsageVO("TA视角", nz(taView)));
        features.add(new AnalyticsOverviewVO.FeatureUsageVO("行动卡", nz(actionItems)));
        features.add(new AnalyticsOverviewVO.FeatureUsageVO("情绪记录", nz(sentimentLogs)));
        features.add(new AnalyticsOverviewVO.FeatureUsageVO("反馈举报", nz(complaints)));
        features.sort(Comparator.comparingLong(AnalyticsOverviewVO.FeatureUsageVO::count).reversed());
        return features;
    }

    // ==================== 内部工具 ====================

    /**
     * {@code col NOT LIKE '前缀%' AND ...}（每个前缀一个 {@code ?}，与 prefixArgs 顺序一一对应）；
     * 前缀列表为空时恒真。
     */
    private String notTestUser(String col) {
        List<String> prefixes = properties.getExcludeUsernamePrefixes();
        if (prefixes.isEmpty()) {
            return "1 = 1";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < prefixes.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(col).append(" NOT LIKE ?");
        }
        return sb.append(")").toString();
    }

    /** 前缀转 LIKE 参数（p → p%），顺序与 {@link #notTestUser(String)} 生成的 ? 一一对应。 */
    private Object[] prefixArgs() {
        return properties.getExcludeUsernamePrefixes().stream()
                .map(p -> p + "%")
                .toArray();
    }

    /** 变长拼接（支持 2 段以上：{@code notTestUser} 每出现一次就要多绑一份前缀参数）。 */
    private static Object[] concat(Object[]... parts) {
        int n = 0;
        for (Object[] p : parts) n += p.length;
        Object[] r = new Object[n];
        int off = 0;
        for (Object[] p : parts) {
            System.arraycopy(p, 0, r, off, p.length);
            off += p.length;
        }
        return r;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
