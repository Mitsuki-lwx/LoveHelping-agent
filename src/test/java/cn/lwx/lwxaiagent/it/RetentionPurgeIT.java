package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.infrastructure.retention.RetentionPurge;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h3>ADR-5 的"定期物理清除 + 级联删向量"（长期只存在于文档里）</h3>
 *
 * <p>2026-10-01 审计：ADR-5 承诺的三步里，**后两步从未实现** ——
 * `物理清除` 全仓只出现在 `Message.java` 的一句注释里；全仓没有任何删向量的代码路径。
 * 本 IT 用**真容器**把这两条路径钉住（复用 {@link RetentionPurge} 里与生产**同一份 SQL**）。</p>
 *
 * <p>⛔ 判据重点不是"删成功了"，而是**边界**：不该删的一条都不能删
 * （未软删的消息、未过期的消息、别的用户的向量、共享知识库向量）。
 * 删错是不可逆事故，边界比功能重要。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 保留期物理清除与向量级联（ADR-5）")
class RetentionPurgeIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate mysql() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    private static JdbcTemplate pg() {
        JdbcTemplate jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS vector_store (id uuid DEFAULT gen_random_uuid() PRIMARY KEY, "
                + "content text, metadata jsonb, embedding vector(3))");
        return jdbc;
    }

    @Test
    @DisplayName("物理清除：只删「已软删 **且** 超过保留期」的消息，未软删/未过期的一条不动")
    void purge_only_deletes_old_soft_deleted() {
        JdbcTemplate jdbc = mysql();
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        // 三种情形：① 软删+过期（应删）② 软删+未过期（不删）③ 未软删+过期（不删）
        insertMessage(jdbc, "it_old_deleted", 1, Instant.now().minus(40, ChronoUnit.DAYS));
        insertMessage(jdbc, "it_new_deleted", 1, Instant.now().minus(2, ChronoUnit.DAYS));
        insertMessage(jdbc, "it_old_alive", 0, Instant.now().minus(40, ChronoUnit.DAYS));

        int rows = RetentionPurge.purgeDeletedMessages(jdbc, cutoff);

        assertThat(rows).as("恰好且只有 1 条符合条件").isEqualTo(1);
        assertThat(ids(jdbc)).as("保留期内的软删消息不能被删（用户可能还想恢复）")
                .contains("it_new_deleted", "it_old_alive")
                .doesNotContain("it_old_deleted");
    }

    @Test
    @DisplayName("幂等：清除任务重复跑不会误删（第二轮 0 行）")
    void purge_is_idempotent() {
        JdbcTemplate jdbc = mysql();
        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        insertMessage(jdbc, "it_idem", 1, Instant.now().minus(40, ChronoUnit.DAYS));
        assertThat(RetentionPurge.purgeDeletedMessages(jdbc, cutoff)).isPositive();
        assertThat(RetentionPurge.purgeDeletedMessages(jdbc, cutoff))
                .as("第二轮不该再有可删的").isZero();
    }

    @Test
    @DisplayName("向量级联：只删该用户的 memory 向量 —— 别人的、以及共享知识库向量一条不动")
    void purge_user_memory_vectors_only_touches_that_user() {
        JdbcTemplate jdbc = pg();
        insertVector(jdbc, "memory", "alice", "alice 的私信摘要");
        insertVector(jdbc, "memory", "bob", "bob 的私信摘要");
        insertVector(jdbc, "knowledge", "alice", "共享知识库片段（署名 alice 是巧合）");

        int deleted = RetentionPurge.purgeUserMemoryVectors(jdbc, "alice");

        assertThat(deleted).as("只删 alice 的 1 条 memory 向量").isEqualTo(1);
        List<String> left = jdbc.queryForList("SELECT content FROM vector_store", String.class);
        assertThat(left).as("⛔ 别人的向量与共享知识库**绝不能**被连带删掉（不可逆）")
                .containsExactlyInAnyOrder("bob 的私信摘要", "共享知识库片段（署名 alice 是巧合）");
    }

    @Test
    @DisplayName("⛔ 空 userId 必须被拒 —— 越界删除不可逆，宁可不删")
    void empty_user_is_rejected() {
        JdbcTemplate jdbc = pg();
        assertThatThrownBy(() -> RetentionPurge.purgeUserMemoryVectors(jdbc, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void insertMessage(JdbcTemplate jdbc, String cid, int deleted, Instant createdAt) {
        jdbc.update("INSERT INTO message (conversation_id, role, content, deleted, created_at) VALUES (?,?,?,?,?)",
                cid, "user", "content-" + cid, deleted, java.sql.Timestamp.from(createdAt));
    }

    private static void insertVector(JdbcTemplate jdbc, String source, String userId, String content) {
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (?, ?::jsonb)",
                content, "{\"source\":\"" + source + "\",\"userId\":\"" + userId + "\"}");
    }

    private static List<String> ids(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT conversation_id FROM message WHERE conversation_id LIKE 'it_%'", String.class);
    }
}
