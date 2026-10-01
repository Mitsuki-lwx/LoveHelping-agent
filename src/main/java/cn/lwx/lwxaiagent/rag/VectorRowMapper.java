package cn.lwx.lwxaiagent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * <h3>vector_store 的**载荷契约**（ADR-46 的正面）</h3>
 *
 * <p><b>为什么单独成类</b>：这段"SQL 行 → {@link Document}"的映射原本是
 * {@link KnowledgeSqlSearch} 的 private 方法 —— 而集成测试层（ADR-69）**不启动 Spring 上下文**，
 * private 方法就**测不到**。ADR-46 的事故正是发生在这一层：手写 SQL 顶掉框架 VectorStore 时，
 * <b>载荷契约没人核对</b>，正文被当成 id，两个后果都**静默**：
 * 下游 {@code getText()} 拿到 UUID（重排/注入全废）、Document id 每次调用都变（RRF 融合永远配不上对）。</p>
 *
 * <p>⇒ 抽成 public static 之后，`VectorPayloadIT` 能在**真 pgvector 容器**上
 * 用**同一份 SQL 片段 + 同一个映射函数**把这两条契约钉死。</p>
 */
@Slf4j
public final class VectorRowMapper {

    private VectorRowMapper() {
    }

    /**
     * 「知识块」的判定：{@code metadata} 里**根本没有 {@code source} 这个键**
     * （实测 439 条知识块 {@code metadata->>'source'} 全是 NULL，而用户记忆显式为 {@code 'memory'}）。
     * ⛔ **必须 {@code COALESCE}**：SQL 三值逻辑下 {@code NULL <> 'memory'} 求值为 NULL →
     * WHERE 里算 false → 知识块会被**静默全部漏掉**。
     */
    public static final String KNOWLEDGE_ONLY =
            "COALESCE(metadata->>'source','') NOT IN ('memory', 'evolution')";

    /**
     * ⛔ {@code content} **必须**在 SELECT 里 —— 少了它，映射出来的 Document 正文就成了 id（ADR-46）。
     */
    public static final String SELECT_COLUMNS = "id, content, metadata::text";

    /**
     * SQL 行 → Document（含 metadata 解析）。各通道共用，避免解析逻辑漂移。
     *
     * <p>⛔ <b>必须用三参构造 {@code (id, text, metadata)}</b>（2026-09-25 修复，ADR-46）。
     * Spring AI 的 {@code Document(String, Map)} 是 <b>{@code (text, metadata)}</b> ——
     * 写成两参会把<b>库里的 id 当成正文</b>，并由 {@code RandomIdGenerator} 给 Document
     * <b>随机生成一个新 id</b>（反编译确认）。两个后果都静默，见类注释。</p>
     */
    public static Document toDocument(ResultSet rs, ObjectMapper json) throws SQLException {
        Map<String, Object> meta = new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rs.getString("metadata"), Map.class);
            if (parsed != null) {
                meta.putAll(parsed);
            }
        } catch (Exception e) {
            log.warn("RAG metadata parse failed: {}", e.getMessage());
        }
        return new Document(rs.getString("id"), rs.getString("content"), meta);
    }
}
