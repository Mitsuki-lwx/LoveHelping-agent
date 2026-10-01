package cn.lwx.lwxaiagent.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>集成测试层（docs/09 §3，ADR-69）：真 MySQL 容器上跑**真迁移链**</h3>
 *
 * <p><b>为什么需要这一层</b>：48 个测试类**全是 Mockito 单测**，没有一层在真数据库上跑过
 * —— 单测里 mapper 被 mock 掉，SQL/约束/默认值/迁移顺序**从来没被真正执行过**。
 * 本仓踩过的"载荷契约"类事故（如 ADR-46：知识块正文被当成 id 读走）正是这一层的盲区：
 * mock 的 mapper 永远返回你想要的东西，**量具对要防的错误免疫**。</p>
 *
 * <p><b>纪律</b>：
 * <ul>
 *   <li>⛔ **不是"能跑通就算过"** —— 每条断言都指向一个**具体契约**（列存在/默认值/种子数据/往返一致），
 *       而不是"应用起来了"。</li>
 *   <li>⛔ **不硬编码迁移条数** —— 加一条迁移就红的话，这条断言守的是"迁移没变"而不是"迁移正确"。</li>
 *   <li>⛔ Docker 缺失时**跳过而不是假绿**（{@code disabledWithoutDocker}）。</li>
 *   <li>⛔ 由 failsafe 在 {@code mvn verify} 跑；{@code mvn test} 保持零外部依赖。</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · MySQL 迁移链与关键契约")
class MysqlMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    private static MigrateResult migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        return flyway.migrate();
    }

    private static List<String> query(String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) sb.append('|');
                    sb.append(rs.getString(i));
                }
                rows.add(sb.toString());
            }
        }
        return rows;
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("迁移链在空库上跑通，且**重复执行不再生效**（幂等）")
    void migrations_apply_and_are_idempotent() throws Exception {
        // ⛔ 必须是**真的空库**：容器是 static 共享的，别的用例也会调 migrate() →
        //    不先 clean 的话，"空库"这个前提只在"恰好第一个跑"时成立（**用例间顺序依赖**，
        //    实测就是这么红的）。所以这里显式 clean 后再断言。
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();

        MigrateResult first = flyway.migrate();
        assertThat(first.success).as("首次迁移必须成功").isTrue();
        assertThat(first.migrationsExecuted).as("空库上至少要有迁移被执行").isPositive();

        MigrateResult second = flyway.migrate();
        assertThat(second.migrationsExecuted)
                .as("第二次不得再执行任何迁移 —— 非幂等意味着每次启动都在改结构").isZero();
    }

    @Test
    @DisplayName("V25 契约：guardrail_rule.scope 存在、默认 BOTH，且 self_harm **每一行**都被置为 INPUT（ADR-55）")
    void scope_column_contract() throws Exception {
        migrate();
        List<String> def = query("SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='agentdb' AND TABLE_NAME='guardrail_rule' AND COLUMN_NAME='scope'");
        assertThat(def).as("scope 列必须存在").hasSize(1);
        assertThat(def.get(0)).as("默认必须是 BOTH —— 否则既有规则行为会静默改变").isEqualTo("BOTH");

        // ⛔ self_harm 在 V18 是**每个关键词一行**（rule_id 重复），不是一行 ——
        //    所以断言"行数>0 且**每一行**都是 INPUT"，而不是 hasSize(1)（实测踩过）。
        //    同理 V25 的 UPDATE 是按 rule_id 批量更新的，若断成单行就测错了对象）。
        List<String> selfHarm = query("SELECT scope FROM guardrail_rule WHERE rule_id='self_harm'");
        assertThat(selfHarm).as("self_harm 规则必须存在（V18 播种，V25 的目标）").isNotEmpty();
        assertThat(selfHarm).as("ADR-55：self_harm 的**每一行**都必须是 INPUT 侧")
                .allSatisfy(s -> assertThat(s).isEqualTo("INPUT"));
    }

    @Test
    @DisplayName("V15 数据契约：情绪刹车片种子规则就位且都是 L2（ADR-6 降温类）")
    void emotion_brake_seeds() throws Exception {
        migrate();
        List<String> rows = query("SELECT rule_id, level, enabled FROM guardrail_rule "
                + "WHERE rule_id LIKE 'emotion_brake%' ORDER BY rule_id");
        assertThat(rows.size()).as("V15 的刹车片种子规则必须存在").isGreaterThanOrEqualTo(6);
        assertThat(rows).allSatisfy(r -> {
            String[] p = r.split("\\|");
            assertThat(p[1]).as("刹车片属 L2：" + r).isEqualTo("2");
            assertThat(p[2]).as("种子规则必须是启用的，否则刹车片永远不触发：" + r).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("V30 契约：有害建议规则进了规则表且 scope=OUTPUT（ADR-6 规则外置；两路径共用）")
    void harmful_advice_rules_are_output_scoped() throws Exception {
        migrate();
        List<String> rows = query("SELECT pattern, scope, level FROM guardrail_rule "
                + "WHERE rule_id='harmful_advice' ORDER BY pattern");
        assertThat(rows).as("V30 的 7 条种子必须都在").hasSize(7);
        assertThat(rows).allSatisfy(r -> {
            String[] p = r.split("\\|");
            assertThat(p[1]).as("必须是 OUTPUT —— 这些词针对助手回复，不该拿去拦用户输入：" + r).isEqualTo("OUTPUT");
            assertThat(p[2]).as("L3 才拦：" + r).isEqualTo("3");
        });
        // 反向：不能把用户输入侧也一起拦了（用户说"我想报复他"是在倾诉）
        List<String> inputHit = query("SELECT rule_id FROM guardrail_rule "
                + "WHERE rule_id='harmful_advice' AND scope IN ('INPUT','BOTH')");
        assertThat(inputHit).as("输入侧不该受这条影响").isEmpty();
    }

    @Test
    @DisplayName("V6 契约：message.feedback 默认 NONE（软反馈免连表）")
    void message_feedback_default() throws Exception {
        migrate();
        List<String> def = query("SELECT COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA='agentdb' AND TABLE_NAME='message' AND COLUMN_NAME='feedback'");
        assertThat(def).as("feedback 列必须存在").hasSize(1);
        assertThat(def.get(0)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("载荷往返：写进 message 的正文必须逐字读回（**不许再出 ADR-46 那类字段错位**）")
    void message_content_round_trip() throws Exception {
        migrate();
        String marker = "IT-往返-" + System.nanoTime() + "-\"引号\"-\\反斜杠-中文";
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO message (conversation_id, role, content) VALUES "
                    + "('it_conv', 'user', '" + marker.replace("\\", "\\\\").replace("'", "''") + "')");
        }
        List<String> got = query("SELECT content, role, feedback, deleted FROM message WHERE conversation_id='it_conv'");
        assertThat(got).as("写入的消息必须能读回").hasSize(1);
        assertThat(got.get(0)).as("正文必须逐字一致；默认值必须生效")
                .isEqualTo(marker + "|user|NONE|0");
    }

    @Test
    @DisplayName("约束真的在生效：role/content 为 NULL 必须被拒（而不是静默写入）")
    void not_null_constraints_enforced() throws Exception {
        migrate();
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                        exec("INSERT INTO message (conversation_id, role) VALUES ('it_conv2', 'user')")))
                .as("content 是 NOT NULL —— 没有约束的库会让脏数据一路走到线上")
                .isNotNull();
    }
}
