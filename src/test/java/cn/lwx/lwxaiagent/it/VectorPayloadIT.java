package cn.lwx.lwxaiagent.it;

import cn.lwx.lwxaiagent.rag.VectorRowMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <h3>vector_store 的载荷契约（ADR-46 的回归哨兵）</h3>
 *
 * <p>ADR-46 是一次静默 P0：手写 SQL 顶掉框架 VectorStore 后，{@code Document} 用了两参构造
 * → 库里的 id 被当成正文、且 id 每次调用都随机变。日志正常、重排"成功"，只是 MRR 0.82 → 0.28。
 * 而当时没有任何测试守这条契约（映射函数此前是 private，容器层够不到）。</p>
 *
 * <p>本 IT 用生产同一份 SQL 片段 + 同一个映射函数（{@link VectorRowMapper}）在真 pgvector 上钉四条：
 * ① 正文就是正文（不是 id）；② id 稳定（RRF 融合靠它配对）；③ {@code COALESCE} 三值逻辑陷阱
 * （metadata 没有 source 键的知识块必须**仍被查到**）；④ metadata 解析不丢。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · 向量载荷契约（ADR-46 回归哨兵）")
class VectorPayloadIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KNOWLEDGE_TEXT = "煤气灯效应：否认你的记忆与感受";
    private static final String MEMORY_TEXT = "用户提到过：TA 最近在准备考试";

    private static JdbcTemplate pg() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE IF NOT EXISTS vector_store (id uuid DEFAULT gen_random_uuid() PRIMARY KEY, "
                + "content text, metadata jsonb, embedding vector(3))");
        return jdbc;
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.execute("DELETE FROM vector_store");
        // ⭐ 知识块：metadata **故意不含 source 键** —— 与线上 439 条知识块的真实形态一致
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (?::text, ?::jsonb)",
                KNOWLEDGE_TEXT, "{\"parent_id\":\"p-1\",\"doc_hash\":\"h1\"}");
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (?::text, ?::jsonb)",
                MEMORY_TEXT, "{\"source\":\"memory\",\"userId\":\"u1\"}");
        jdbc.update("INSERT INTO vector_store (content, metadata) VALUES (?::text, ?::jsonb)",
                "进化技能产物", "{\"source\":\"evolution\"}");
    }

    private static List<Document> knowledgeRows(JdbcTemplate jdbc) {
        return jdbc.query("SELECT " + VectorRowMapper.SELECT_COLUMNS + " FROM vector_store WHERE "
                        + VectorRowMapper.KNOWLEDGE_ONLY,
                (rs, row) -> VectorRowMapper.toDocument(rs, JSON));
    }

    @Test
    @DisplayName("① 正文就是正文：getText() 是 content 列，**绝不是 id**（ADR-46 主症状）")
    void text_is_content_not_id() {
        JdbcTemplate jdbc = pg();
        seed(jdbc);
        List<Document> docs = knowledgeRows(jdbc);
        assertThat(docs).as("知识块必须被查到（见下条 COALESCE 陷阱）").hasSize(1);
        Document d = docs.get(0);
        assertThat(d.getText()).as("⛔ 正文被换成 id 就是 ADR-46 复发").isEqualTo(KNOWLEDGE_TEXT);
        assertThat(d.getText()).as("正文不该长得像 UUID").doesNotMatch("^[0-9a-f-]{36}$");
        assertThat(d.getId()).as("id 也不该等于正文").isNotEqualTo(KNOWLEDGE_TEXT);
    }

    @Test
    @DisplayName("② id 稳定：同一行两次映射得到同一个 id（否则 RRF 融合永远配不上对）")
    void id_is_stable_across_calls() {
        JdbcTemplate jdbc = pg();
        seed(jdbc);
        String first = knowledgeRows(jdbc).get(0).getId();
        String second = knowledgeRows(jdbc).get(0).getId();
        assertThat(first).as("id 每次调用都变会让同一块占两个候选位（ADR-46 的第二个静默后果）")
                .isEqualTo(second);
    }

    @Test
    @DisplayName("③ COALESCE 陷阱：metadata 没有 source 键的知识块**必须仍被查到**（不能静默滤光）")
    void knowledge_without_source_key_is_still_found() {
        JdbcTemplate jdbc = pg();
        seed(jdbc);
        assertThat(knowledgeRows(jdbc)).as("裸比较 metadata->>'source' <> 'memory' 会因三值逻辑把知识块全滤掉")
                .hasSize(1);
        // 对照：裸比较会**漏掉没有 source 键的知识块**（三值逻辑），但**不会**漏 `source='evolution'`
        // ⛔ 我第一版断言"裸比较数到 0 条"是错的 —— 忘了 evolution 行也满足 `<> 'memory'`。
        //    对照断言的前提同样要验证（同族错误：断言的前提本身也是待验证的断言）。
        List<String> bare = jdbc.queryForList(
                "SELECT content FROM vector_store WHERE metadata->>'source' <> 'memory'", String.class);
        assertThat(bare).as("裸比较只数得到 evolution 行（知识块被三值逻辑滤掉）")
                .containsExactly("进化技能产物");
        assertThat(bare).as("这正是必须 COALESCE 的原因：知识块会被静默滤光")
                .doesNotContain(KNOWLEDGE_TEXT);
    }

    @Test
    @DisplayName("④ metadata 解析不丢：parent_id / doc_hash 要能读出来")
    void metadata_is_parsed() {
        JdbcTemplate jdbc = pg();
        seed(jdbc);
        Document d = knowledgeRows(jdbc).get(0);
        assertThat(d.getMetadata()).containsEntry("parent_id", "p-1").containsEntry("doc_hash", "h1");
    }
}
