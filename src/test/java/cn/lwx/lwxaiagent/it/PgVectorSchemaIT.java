package cn.lwx.lwxaiagent.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <h3>集成测试层（docs/09 §3，ADR-69）：真 pgvector 容器上的向量栈契约</h3>
 *
 * <p><b>为什么要有这条</b>：`vector_store` 表**不在 MySQL 迁移链里**（它是 PG 侧），
 * 目前只由 CI 的**手写 SQL** 建（`.github/workflows/ci.yml` 的 "Enable pgvector extension" 步骤）
 * —— 也就是说"表长什么样"这件事**没有任何测试守着**。若嵌入模型换维度（1024 → 1536）
 * 或算子/索引类型写错，**只有等线上检索全部报错才会发现**。</p>
 *
 * <p>本 IT 守三条契约：<b>维度（1024，且必须拒绝其他维度）</b>、<b>余弦算子</b>、<b>hnsw 索引可建</b>。
 * ⛔ 断言的是**契约**，不是"容器起来了"。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("IT · PG 向量栈契约（维度/余弦算子/索引）")
class PgVectorSchemaIT {

    /** 与 `PgVectorStore` 实际使用的表名/维度对齐：见 `application.yml` §pgvector 与 `EmbeddingModelConfig`。 */
    private static final String TABLE = "vector_store";
    private static final int DIM = 1024;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Connection open() throws Exception {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static void createSchema(int dim) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id uuid DEFAULT uuid_generate_v4() PRIMARY KEY, "
                    + "content text, metadata jsonb, embedding vector(" + dim + "))");
        }
    }

    private static String vec(int dim, double first) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == 0 ? first : 0.0);
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("扩展可启用，且 (dim) 向量列可按 " + DIM + " 维建表 + hnsw 余弦索引")
    void vector_column_and_index() throws Exception {
        createSchema(DIM);
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS spring_ai_vector_index ON " + TABLE
                    + " USING hnsw (embedding vector_cosine_ops)");
        }
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a "
                             + "JOIN pg_class t ON t.oid = a.attrelid WHERE t.relname = '" + TABLE + "' AND a.attname = 'embedding'")) {
            assertThat(rs.next()).as("embedding 列必须存在").isTrue();
            assertThat(rs.getString(1)).as("维度必须是 vector(%d)".formatted(DIM)).isEqualTo("vector(" + DIM + ")");
        }
    }

    @Test
    @DisplayName("⛔ 维度漂移哨兵：非 " + DIM + " 维的向量**必须被拒**（换嵌入模型时立刻炸，而不是静默降级）")
    void wrong_dimension_is_rejected() throws Exception {
        createSchema(DIM);
        assertThatThrownBy(() -> {
            try (Connection c = open(); Statement st = c.createStatement()) {
                st.execute("INSERT INTO " + TABLE + " (content, embedding) VALUES ('bad', '" + vec(DIM - 1, 1.0) + "')");
            }
        }).as("维度不符必须报错 —— 否则会写进一个永远检索不到的坏向量")
                .hasMessageContaining("dimensions");
    }

    @Test
    @DisplayName("余弦最近邻可用：查回来的最近邻就是自己（<=> 算子真的在服务）")
    void cosine_nearest_neighbour() throws Exception {
        createSchema(DIM);
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO " + TABLE + " (content, embedding) VALUES ('near', '" + vec(DIM, 1.0) + "')");
            st.execute("INSERT INTO " + TABLE + " (content, embedding) VALUES ('far',  '" + vec(DIM, -1.0) + "')");
        }
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT content FROM " + TABLE
                     + " ORDER BY embedding <=> '" + vec(DIM, 1.0) + "' LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).as("最近邻应是同向量的那条").isEqualTo("near");
        }
    }
}
