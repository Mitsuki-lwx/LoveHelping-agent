package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.infrastructure.retention.RetentionPurge;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>残留向量补扫（ADR-75 / phase33 R3）在**真 MySQL + 真 pgvector** 上的行为</h3>
 *
 * <p>为什么必须用容器：这条链路跨两个库（MySQL 认"谁是已注销账号"、PG 删向量），
 * 单测里 mock 任一侧都会**测不到真正的 SQL** —— 而这正是"载荷契约"类事故的藏身处（ADR-46）。</p>
 *
 * <p>钉四条：⛔ 只认 {@code enabled = false} 的账号 · 补扫后该用户的向量**真的没了** ·
 * **别人与共享知识库的向量一条不动** · **幂等**（第二轮删 0 条）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 残留向量补扫（ADR-75）")
class VectorRescanIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate mysql() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    private static JdbcTemplate pg() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS vector_store (id uuid DEFAULT gen_random_uuid() PRIMARY KEY, "
                + "content text, metadata jsonb, embedding vector(3))");
        jdbc.execute("DELETE FROM vector_store");
        return jdbc;
    }

    private static void user(JdbcTemplate jdbc, String name, boolean enabled) {
        jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, 'x', ?)", name, enabled);
    }

    private static void vector(JdbcTemplate jdbc, String source, String userId, String text) {
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (?::text, ?::jsonb)",
                text, "{\"source\":\"" + source + "\",\"userId\":\"" + userId + "\"}");
    }

    @BeforeEach
    void reset() {
        mysql().update("DELETE FROM users");
        pg();
    }

    /** 模拟一轮补扫（与 VectorRescanScheduler 的循环一致）。 */
    private static int rescanOnce(JdbcTemplate mysql, JdbcTemplate pg) {
        int total = 0;
        for (String uid : RetentionPurge.findDisabledUserIds(mysql)) {
            total += RetentionPurge.purgeUserMemoryVectors(pg, uid);
        }
        return total;
    }

    @Test
    @DisplayName("⛔ 补扫只认**已注销**账号：enabled=true 的用户不在名单里")
    void only_disabled_users_are_candidates() {
        JdbcTemplate mysql = mysql();
        user(mysql, "gone_user", false);
        user(mysql, "active_user", true);

        assertThat(RetentionPurge.findDisabledUserIds(mysql))
                .as("⛔ 把正常用户也扫了 = 误删活跃用户的记忆，那是事故不是清理")
                .containsExactly("gone_user");
    }

    @Test
    @DisplayName("补扫后：已注销用户的记忆向量**真的被删掉**（跨两个库的真链路）")
    void rescan_removes_deleted_users_vectors() {
        JdbcTemplate mysql = mysql();
        JdbcTemplate pg = pg();
        user(mysql, "gone_user", false);
        vector(pg, "memory", "gone_user", "gone 的私信摘要 1");
        vector(pg, "memory", "gone_user", "gone 的私信摘要 2");

        int deleted = rescanOnce(mysql, pg);

        assertThat(deleted).isEqualTo(2);
        assertThat(pg.queryForList("SELECT content FROM vector_store", String.class)).isEmpty();
    }

    @Test
    @DisplayName("⛔ 别人的记忆向量与共享知识库向量**一条都不动**")
    void rescan_does_not_touch_others() {
        JdbcTemplate mysql = mysql();
        JdbcTemplate pg = pg();
        user(mysql, "gone_user", false);
        vector(pg, "memory", "gone_user", "gone 的摘要");
        vector(pg, "memory", "alive_user", "别人的摘要");
        vector(pg, "evolution", "gone_user", "进化技能产物");

        rescanOnce(mysql, pg);

        assertThat(pg.queryForList("SELECT content FROM vector_store", String.class))
                .as("越界删除不可逆：别人的与共享的必须原样留下")
                .containsExactlyInAnyOrder("别人的摘要", "进化技能产物");
    }

    @Test
    @DisplayName("幂等：第二轮扫到 0 条（补扫靠'反复收敛'，不是靠'一次全成'）")
    void rescan_is_idempotent() {
        JdbcTemplate mysql = mysql();
        JdbcTemplate pg = pg();
        user(mysql, "gone_user", false);
        vector(pg, "memory", "gone_user", "gone 的摘要");

        assertThat(rescanOnce(mysql, pg)).isEqualTo(1);
        assertThat(rescanOnce(mysql, pg))
                .as("已删干净的账号再来扫必须安静地删 0 条，而不是报错或重复动")
                .isZero();
    }

    @Test
    @DisplayName("没有已注销账号时：名单为空，不做任何删除（不打扰正常库）")
    void no_disabled_users_is_noop() {
        JdbcTemplate mysql = mysql();
        JdbcTemplate pg = pg();
        user(mysql, "active_user", true);
        vector(pg, "memory", "active_user", "活跃用户的摘要");

        assertThat(rescanOnce(mysql, pg)).isZero();
        assertThat(pg.queryForList("SELECT content FROM vector_store", String.class)).hasSize(1);
    }
}
