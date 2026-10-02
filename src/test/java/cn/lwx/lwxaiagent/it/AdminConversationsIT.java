package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.memory.MemoryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * <h3>管理端会话列表的**用户维度**（ADR-77 / phase33 前端测试发现的 D2）</h3>
 *
 * <p><b>为什么补这个 IT</b>：管理端有个指标叫「用户数」，但原始 SQL 只
 * {@code SELECT conversation_id, COUNT(*), MIN(created_at)} —— **根本没有 user_id**，
 * 前端只能按会话去重 ⇒ 「用户数」恒等于「总对话数」（同一用户开多个会话被算成多个人）。
 * 这是**统计口径错**，界面上看不出异常，只是数字悄悄错。</p>
 *
 * <p>⛔ 修法是 SQL 带上 {@code MIN(user_id) AS user_id}。本 IT 在真 MySQL 上钉两件事：
 * 字段**真的在**；同一用户的两个会话**共享同一个 user_id**（前端据此去重才有意义）。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 管理端会话列表带用户维度（ADR-77）")
class AdminConversationsIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("agentdb");

    private static JdbcTemplate mysql() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    private static MemoryService service(JdbcTemplate jdbc) {
        return new MemoryService(mock(cn.lwx.lwxaiagent.mapper.MessageMapper.class), jdbc,
                mock(cn.lwx.lwxaiagent.infrastructure.EncryptionService.class));
    }

    private static void msg(JdbcTemplate jdbc, String conv, String user, String content) {
        jdbc.update("INSERT INTO message (conversation_id, user_id, role, content, deleted) VALUES (?,?,?,?,0)",
                conv, user, "USER", content);
    }

    @BeforeEach
    void reset() {
        mysql().update("DELETE FROM message");
    }

    @Test
    @DisplayName("⭐ 结果里必须带 user_id（没有它，前端的「用户数」永远算不对）")
    void result_contains_user_id() {
        JdbcTemplate jdbc = mysql();
        msg(jdbc, "c1", "u1", "甲");

        List<Map<String, Object>> rows = service(jdbc).listAllConversations();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).as("缺 user_id ⇒ 前端只能按会话去重（D2 的根因）").containsKey("user_id");
        assertThat(rows.get(0).get("user_id")).isEqualTo("u1");
        assertThat(rows.get(0)).containsKeys("conversation_id", "message_count", "created_at");
    }

    @Test
    @DisplayName("⭐ 同一用户的多个会话共享同一 user_id（前端据此去重才有意义）")
    void same_user_shares_user_id_across_conversations() {
        JdbcTemplate jdbc = mysql();
        msg(jdbc, "c1", "u1", "甲-1");
        msg(jdbc, "c1", "u1", "甲-2");
        msg(jdbc, "c2", "u1", "甲-3");   // 同一个人，另一个会话
        msg(jdbc, "c3", "u2", "乙-1");

        List<Map<String, Object>> rows = service(jdbc).listAllConversations();

        assertThat(rows).hasSize(3);
        assertThat(rows.stream().map(r -> r.get("user_id")).distinct().toList())
                .as("3 个会话只属于 2 个用户 —— 这才是「用户数」该显示的值")
                .containsExactlyInAnyOrder("u1", "u2");
        assertThat(rows.stream().filter(r -> "c1".equals(r.get("conversation_id"))).findFirst().orElseThrow()
                .get("message_count")).as("c1 的条数按会话聚合").isEqualTo(2L);
    }

    @Test
    @DisplayName("软删的消息不计入（口径与页面上的「总对话数/总消息数」一致）")
    void deleted_messages_excluded() {
        JdbcTemplate jdbc = mysql();
        msg(jdbc, "c1", "u1", "保留");
        jdbc.update("INSERT INTO message (conversation_id, user_id, role, content, deleted) VALUES ('c2','u1','USER','已删',1)");

        List<Map<String, Object>> rows = service(jdbc).listAllConversations();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("conversation_id")).isEqualTo("c1");
    }
}
