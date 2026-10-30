package cn.lwx.lwxaiagent.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h3>AnalyticsService 口径测试（钉住口径边界，不只测 happy path）</h3>
 *
 * <p>用 mock 的 {@link JdbcTemplate} 断言 SQL 语义与组装逻辑：</p>
 * <ul>
 *   <li>排除前缀生效：合成账号不进 activeUsers/newUsers，且 excludedTestUsers 如实上报；</li>
 *   <li>SQL 必须排除 anonymous、必须带 deleted=0、必须有界（created_at 下界）；</li>
 *   <li>days 越界夹到 [1,90]；series 长度 = days 且按天补齐、升序；</li>
 *   <li>无 cohort 时 d1/d7 = 0 而不是 NaN；</li>
 *   <li>funnel 顺序固定；features 降序、只出计数（null → 0）。</li>
 * </ul>
 *
 * <p>stub 按 SQL 内容分发（不能用"后来者覆盖"式的宽匹配——漏斗与 features 都是
 * COUNT 型 queryForObject，宽匹配会把先注册的 stub 吃掉）。</p>
 */
@DisplayName("AnalyticsService：看板口径与边界")
class AnalyticsServiceTest {

    private JdbcTemplate jdbc;
    private AnalyticsProperties props;
    private AnalyticsService service;

    /** 固定"今天"，避免测试跨日漂移。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        props = new AnalyticsProperties();
        service = new AnalyticsService(jdbc, props) {
            @Override
            public AnalyticsOverviewVO overview(int days) {
                return overviewFixedToday(days, TODAY);
            }
        };
    }

    /**
     * 复用真实组装逻辑，但把"今天"固定为 {@link #TODAY}——不依赖时钟，跨日不漂移。
     * 组装步骤若与 service.overview 失配，Controller 测试与全量回归兜底。
     */
    private AnalyticsOverviewVO overviewFixedToday(int days, LocalDate today) {
        int clamped = Math.max(AnalyticsService.MIN_DAYS, Math.min(AnalyticsService.MAX_DAYS, days));
        LocalDate start = today.minusDays(clamped - 1L);
        Timestamp from = Timestamp.valueOf(start.atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime());

        long excluded = service.countExcludedTestUsers();
        Map<LocalDate, long[]> daily = service.dailySeries(start, clamped);
        AnalyticsOverviewVO.RetentionVO retention = service.retention(start, clamped);
        List<AnalyticsOverviewVO.FunnelStepVO> funnel = service.funnel(from, today);
        List<AnalyticsOverviewVO.FeatureUsageVO> features = service.features(from);

        List<AnalyticsOverviewVO.DailyPointVO> series = new java.util.ArrayList<>(clamped);
        for (int i = 0; i < clamped; i++) {
            LocalDate d = start.plusDays(i);
            long[] v = daily.getOrDefault(d, new long[3]);
            series.add(new AnalyticsOverviewVO.DailyPointVO(d.toString(), v[0], v[1], v[2]));
        }
        return new AnalyticsOverviewVO(clamped, LocalDateTime.now(), excluded,
                new AnalyticsOverviewVO.DataQualityVO(2642, excluded, excluded / 2642.0, true),
                series, retention, funnel, features);
    }

    // ==================== stub 工具（按 SQL 内容分发） ====================

    /**
     * 所有"计数型" queryForObject（含 excludedTestUsers）：按 SQL 内容分发到各自的值。
     * ⛔ 只注册一次宽 stub（先注册的会被后注册的覆盖），分发全靠 marker。
     * 兜底 0：漏斗"注册"那条没有独特 marker，由调用方显式传 marker。
     */
    private void stubCounts(Map<String, Long> byMarker) {
        java.util.function.Function<String, Long> pick = sql -> {
            for (Map.Entry<String, Long> e : byMarker.entrySet()) {
                if (sql.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return 0L;
        };
        // 三参重载（带参数：排除前缀链那些）
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(inv -> pick.apply(inv.getArgument(0)));
        // ⛔ 两参重载（无参数：用户总数 `SELECT COUNT(*) AS total_users FROM users`）——
        //    漏桩会返回 null ⇒ excluded = 0 − kept ⇒ 夹成 0（实测踩过，表现为"已排除 0 个"）
        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenAnswer(inv -> pick.apply(inv.getArgument(0)));
    }

    /** excludedTestUsers 的 COUNT 查询（users WHERE 无 JOIN 无 EXISTS，唯一 marker）。 */
    /** 非测试用户数（`WHERE <notTestUser>` 那条）。 */
    private void stubCount(Map<String, Long> all, long n) {
        all.put("FROM users WHERE (username", n);
    }

    /**
     * 用户总数（数据质量分母）。
     * ⛔ 键必须唯一：两条 SQL 都是 `COUNT(*) FROM users ...`，靠 `contains` 分不开 ——
     * 所以生产侧给总数查询加了 `AS total_users` 别名，这里按别名匹配。
     */
    private void stubTotalUsers(Map<String, Long> all, long n) {
        all.put("AS total_users", n);
    }

    /**
     * 所有"行回调型" query（RowCallbackHandler 重载）：按 SQL 内容分发。
     * <ul>
     *   <li>含 {@code active_users} → message 日聚合（默认无行）；</li>
     *   <li>含 {@code COUNT(*) AS c} → 注册日聚合（默认无行）；</li>
     *   <li>含 {@code c0} → 留存聚合。</li>
     * </ul>
     * <p>RowCallback 的实现里只负责给 mock 的 ResultSet 打桩；打桩后由本方法统一回调
     * {@code handler.processRow(rs)}（service 侧的 lambda 才会真正读到行）。</p>
     */
    private void stubRowQueries(RowCallback dailyRows, RowCallback regRows, Long c0, Long c1, Long c7) {
        org.mockito.Mockito.doAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    RowCallbackHandler handler = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    if (sql.contains("active_users") && dailyRows != null) {
                        dailyRows.feed(rs);
                    } else if (sql.contains("COUNT(*) AS c") && regRows != null) {
                        regRows.feed(rs);
                    } else if (sql.contains("c0") && c0 != null) {
                        when(rs.getLong("c0")).thenReturn(c0);
                        when(rs.getLong("c1")).thenReturn(c1 == null ? 0 : c1);
                        when(rs.getLong("c7")).thenReturn(c7 == null ? 0 : c7);
                    } else {
                        return null; // 无数据 → handler 不被回调（等价于空结果集）
                    }
                    handler.processRow(rs);
                    return null;
                })
                .when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    /** 行数据供给器：测试用 lambda 往 mock 的 ResultSet 上打桩并回调 handler。 */
    @FunctionalInterface
    interface RowCallback {
        void feed(ResultSet rs) throws Exception;
    }

    // ==================== 排除前缀 ====================

    @Test
    void excludedTestUsers_reportsPrefixedAccounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        // ⛔ 语义（2026-10-14 修正）：excludedTestUsers = **总数 − 非测试数**。
        //    此前直接拿"非测试数"当"已排除"上报 —— 语义整个反了（清单越长数字越小）。
        stubCount(counts, 242L);        // 非测试用户（留下的）
        stubTotalUsers(counts, 2642L);  // 总数 ⇒ 已排除 = 2642 − 242 = 2400
        stubCounts(counts);
        stubRowQueries(null, null, null, null, null);

        AnalyticsOverviewVO vo = service.overview(7);

        assertThat(vo.excludedTestUsers()).isEqualTo(2400L);
    }

    /** 每条涉及"人"的计数 SQL 都必须带 NOT LIKE 排除链（合成账号不许混进任何口径）。 */
    @Test
    void sql_excludesPrefixedUsernamesInEveryCount() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubCounts(counts);
        stubRowQueries(null, null, null, null, null);

        service.overview(7);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getAllValues())
                .allSatisfy(sql -> assertThat(sql).contains("NOT LIKE ?"));
    }

    @Test
    void prefixArgs_bindAsLikePatterns() {
        props.setExcludeUsernamePrefixes(List.of("verify_", "load_"));
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubCounts(counts);
        stubRowQueries(null, null, null, null, null);

        service.countExcludedTestUsers();

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(anyString(), eq(Long.class), args.capture());
        assertThat(args.getValue()).containsExactly("verify_%", "load_%");
    }

    // ==================== anonymous / deleted / 有界 ====================

    @Test
    void dailySql_excludesAnonymousAndDeletedAndBounded() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubCounts(counts);
        stubRowQueries(null, null, null, null, null);

        service.overview(30);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sqlCaptor.capture(), any(RowCallbackHandler.class), any(Object[].class));
        List<String> sqls = sqlCaptor.getAllValues();
        // message 聚合 SQL：排除 anonymous、deleted=0、时间有界、活跃口径是去重
        assertThat(sqls).anySatisfy(sql -> {
            assertThat(sql).contains("user_id <> 'anonymous'");
            assertThat(sql).contains("deleted = 0");
            assertThat(sql).contains("created_at >= ?");
            assertThat(sql).contains("created_at < ?");
            assertThat(sql).contains("COUNT(DISTINCT");
        });
        // 注册聚合 SQL：有界 + 非测试
        assertThat(sqls).anySatisfy(sql -> {
            assertThat(sql).contains("FROM users u");
            assertThat(sql).contains("created_at >= ?");
            assertThat(sql).contains("NOT LIKE ?");
        });
    }

    @Test
    void retention_ratiosComputedFromCohort() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, null, 50L, 21L, 9L);
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(30);

        assertThat(vo.retention().cohortSize()).isEqualTo(50L);
        assertThat(vo.retention().d1()).isEqualTo(0.42);
        assertThat(vo.retention().d7()).isEqualTo(0.18);
    }

    // ==================== days 夹取 + series 补齐 ====================

    @Test
    void days_clampedToRange() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubCounts(counts);
        stubRowQueries(null, null, null, null, null);

        assertThat(service.overview(0).days()).isEqualTo(1);
        assertThat(service.overview(-5).days()).isEqualTo(1);
        assertThat(service.overview(91).days()).isEqualTo(90);
        assertThat(service.overview(9999).days()).isEqualTo(90);
        assertThat(service.overview(30).days()).isEqualTo(30);
    }

    @Test
    void series_lengthEqualsDaysAndFilledPerDay() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        // message 日聚合只有 2026-10-12 一行；注册聚合无行 —— 其余天必须补 0，不许跳天
        stubRowQueries(rs -> {
            when(rs.getDate("d")).thenReturn(Date.valueOf("2026-10-12"));
            when(rs.getLong("active_users")).thenReturn(3L);
            when(rs.getLong("msg_cnt")).thenReturn(12L);
        }, null, null, null, null);
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(5);

        assertThat(vo.series()).hasSize(5);
        List<String> dates = vo.series().stream().map(AnalyticsOverviewVO.DailyPointVO::date).toList();
        assertThat(dates).containsExactly("2026-10-10", "2026-10-11", "2026-10-12", "2026-10-13", "2026-10-14");
        assertThat(vo.series().get(0).activeUsers()).isZero();
        assertThat(vo.series().get(0).newUsers()).isZero();
        assertThat(vo.series().get(0).messages()).isZero();
        assertThat(vo.series().get(2).activeUsers()).isEqualTo(3L);
        assertThat(vo.series().get(2).messages()).isEqualTo(12L);
        // 升序
        assertThat(dates).isSorted();
    }

    @Test
    void series_registrationCountsLandOnTheirDay() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, rs -> {
            when(rs.getDate("d")).thenReturn(Date.valueOf("2026-10-11"));
            when(rs.getLong("c")).thenReturn(2L);
        }, null, null, null);
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(5);

        assertThat(vo.series().get(1).newUsers()).isEqualTo(2L);
        assertThat(vo.series().get(0).newUsers()).isZero();
        assertThat(vo.series().get(4).newUsers()).isZero();
    }

    // ==================== 无 cohort → 0 不是 NaN ====================

    @Test
    void retention_noCohort_returnsZeroNotNaN() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, null, 0L, 0L, 0L);
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(7);

        assertThat(vo.retention().cohortSize()).isZero();
        assertThat(vo.retention().d1()).isEqualTo(0.0);
        assertThat(vo.retention().d7()).isEqualTo(0.0);
        assertThat(vo.retention().d1()).isFinite();
        assertThat(vo.retention().d7()).isFinite();
    }

    // ==================== 漏斗 ====================

    @Test
    void funnel_fixedOrderAndCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, null, null, null, null);
        // 漏斗三条 SQL 按 SQL 特征区分：nextDay 有 INTERVAL 2 DAY，firstChat 有 EXISTS（窗口外注册的老用户也算），
        // 注册那条只有 "FROM users u WHERE u.created_at"（无 EXISTS）。
        // ⛔ marker 顺序：短 marker 也命中长 SQL，必须把"次日回访"（最具体）放最前。
        counts.put("m.deleted = 0   AND m.created_at >= DATE_ADD(u.created_at, INTERVAL 1 DAY)", 21L); // 次日回访
        counts.put("EXISTS (SELECT 1 FROM message m WHERE m.user_id = u.username", 45L);             // 首次对话
        counts.put("FROM users u WHERE u.created_at", 50L);                                          // 注册（兜底，放最后）
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(30);

        assertThat(vo.funnel()).extracting(AnalyticsOverviewVO.FunnelStepVO::step)
                .containsExactly("注册", "首次对话", "次日回访");
        assertThat(vo.funnel()).extracting(AnalyticsOverviewVO.FunnelStepVO::count)
                .containsExactly(50L, 45L, 21L);
    }

    // ==================== features ====================

    @Test
    void features_sortedDescending_withFixedNames() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, null, null, null, null);
        // 六个功能计数按 SQL 里的表名分发（查询顺序：聊天/沙盘/TA/行动卡/情绪/反馈）
        counts.put("FROM user_conversations", 50L);
        counts.put("FROM sandbox_session", 3L);
        counts.put("FROM audit_log", 8L);
        counts.put("FROM action_item", 120L);
        counts.put("FROM sentiment_log", 7L);
        counts.put("FROM complaint", 1L);
        stubCounts(counts);

        AnalyticsOverviewVO vo = service.overview(30);

        assertThat(vo.features()).extracting(AnalyticsOverviewVO.FeatureUsageVO::name)
                .containsExactly("行动卡", "聊天会话", "TA视角", "情绪记录", "沙盘会话", "反馈举报");
        assertThat(vo.features().get(0).count()).isEqualTo(120L);
        assertThat(vo.features().get(5).count()).isEqualTo(1L);
    }

    @Test
    void features_nullCountsTreatedAsZero() {
        Map<String, Long> counts = new LinkedHashMap<>();
        stubCount(counts, 0L);
        stubRowQueries(null, null, null, null, null);
        // 不 stub → queryForObject 默认返回 null → 组装层必须给 0，不许 NPE/NaN

        AnalyticsOverviewVO vo = service.overview(30);

        assertThat(vo.features()).hasSize(6);
        assertThat(vo.features()).allSatisfy(f -> assertThat(f.count()).isZero());
    }

    /**
     * <h3>⛔ 机械闸门：SQL 占位符数必须等于绑定参数个数</h3>
     *
     * <p><b>为什么必须有这条</b>：mock 掉 {@link JdbcTemplate} 时，<b>它不会校验参数个数</b> ——
     * 占位符比参数多，单测照样全绿，只有真跑才炸。2026-10-13 实测就中了：
     * {@code dailySeries} 里 {@code notTestUser("m.user_id")} 用了两次（40 个 {@code ?}），
     * 却只绑了一份前缀参数（20 个）⇒ 运行期
     * {@code SQLException: No value specified for parameter 23} ⇒ 接口 500。</p>
     *
     * <p>所以这里遍历**本次组装过程中所有** {@code jdbc.query(...)} / {@code queryForObject(...)}
     * 调用，逐条数 {@code ?} 与实参个数。任何"改了 SQL 忘了补参数"都会被它当场抓住。</p>
     */
    @Test
    void everyQueryBindsExactlyAsManyArgsAsPlaceholders() {
        // 让所有 stub 都能命中（返回空集/0），只要能跑完整条组装链即可
        // RowCallbackHandler 版 query 返回 void（mock 默认什么都不做，无需 stub）
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(java.util.List.of());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);

        try {
            service.overview(7);
        } catch (RuntimeException ignored) {
            // 组装过程本身可能因为空 stub 失败，不影响"已发生的调用都要校验"这一目的
        }

        List<String> offenders = new java.util.ArrayList<>();
        int checked = 0;

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argsCap = ArgumentCaptor.forClass(Object[].class);
        ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);

        // 带参数的两参/三参重载（query(sql, handler|mapper, args...) / queryForObject(sql, type, args...)）
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .query(sqlCap.capture(), any(RowCallbackHandler.class), argsCap.capture());
        checked += checkPairs(sqlCap.getAllValues(), argsCap.getAllValues(), offenders);

        sqlCap = ArgumentCaptor.forClass(String.class);
        argsCap = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .queryForObject(sqlCap.capture(), eq(Long.class), argsCap.capture());
        checked += checkPairs(sqlCap.getAllValues(), argsCap.getAllValues(), offenders);

        assertThat(checked)
                .as("至少要捕获到带参数的查询（否则闸门是空转的）").isGreaterThan(0);
        assertThat(offenders)
                .as("占位符数与参数个数不匹配 —— 运行期会报 No value specified for parameter N")
                .isEmpty();
    }

    /** 逐对比较 SQL 里的 {@code ?} 个数与实参个数；返回检查条数。 */
    private static int checkPairs(List<String> sqls, List<Object[]> argsList, List<String> offenders) {
        int n = Math.min(sqls.size(), argsList.size());
        for (int i = 0; i < n; i++) {
            String sql = sqls.get(i);
            Object[] args = argsList.get(i);
            long q = sql.chars().filter(c -> c == '?').count();
            int a = args == null ? 0 : args.length;
            if (q != a) {
                offenders.add("?=" + q + " 参数=" + a + " :: " + sql.substring(0, Math.min(120, sql.length())));
                continue;
            }
            // ⛔ **顺序/类型**检查：个数对了不代表绑对了。实测踩过：占位符文本顺序是
            //    「前缀×2 → 时间戳」（CASE 写在 SELECT 列表里），而参数按「时间戳 → 前缀」绑
            //    ⇒ 运行期 `Incorrect TIMESTAMP value: 'sent_%'`。
            //    这里按 `created_at <op> ?` 在 SQL 里的**第几个 ?** 反查实参类型。
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("created_at\\s*[<>]=?\\s*\\?").matcher(sql);
            while (mm.find()) {
                int ordinal = (int) sql.substring(0, mm.start()).chars().filter(c -> c == '?').count();
                if (ordinal < a && !(args[ordinal] instanceof java.sql.Timestamp)) {
                    offenders.add("第 " + (ordinal + 1) + " 个参数应是 Timestamp，实际 "
                            + (args[ordinal] == null ? "null" : args[ordinal].getClass().getSimpleName())
                            + " :: " + sql.substring(0, Math.min(90, sql.length())));
                }
            }
        }
        return n;
    }
}
