package cn.lwx.lwxaiagent.rag;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 守两件事：**源过滤真的下推到了 SQL**，且**写法没有 NULL 陷阱**。
 *
 * <p>为什么必须有这一条（ADR-43 的前置实测）：知识块的 metadata **根本没有 `source` 这个键**
 * —— 439 条知识块的 {@code metadata->>'source'} 全是 NULL，而 461 条用户记忆显式为 {@code 'memory'}。
 * 于是 SQL 三值逻辑下 {@code metadata->>'source' <> 'memory'} 对知识块求值为 **NULL** → WHERE 里算 false
 * → **把知识块一起滤光，而且不报错**。这是当初"不敢把过滤下推"的原因，也是本轮最大的回归风险：
 * 将来任何人"顺手简化"成裸比较，检索会静默返回空。</p>
 *
 * <p>所以这里断言的是**真正执行的 SQL 文本**（而不是行为）——
 * 生产路径的 JdbcTemplate 是自建的，只有这个测试构造器能把 mock 注进去。</p>
 */
class KnowledgeSqlSearchTest {

    private JdbcTemplate pg;
    private KnowledgeSqlSearch search;

    @BeforeEach
    void setup() {
        pg = mock(JdbcTemplate.class);
        EmbeddingModel em = mock(EmbeddingModel.class);
        when(em.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
        when(pg.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        search = new KnowledgeSqlSearch(pg, em);
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(pg).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    private static void assertHasSafeFilter(String sql) {
        assertTrue(sql.contains("COALESCE(metadata->>'source'"),
                "必须用 COALESCE 兜住 NULL（知识块没有 source 键）：" + sql);
        assertTrue(sql.contains("NOT IN ('memory','evolution')"), "过滤名单要同时排除记忆与已学技能：" + sql);
        assertFalse(sql.matches("(?s).*metadata->>'source'\\s*(<>|!=).*"),
                "不得退化成裸比较 —— 知识块没有 source 键，NULL 参与比较会让它们被一起滤光：" + sql);
    }

    /** 向量通道：过滤必须与取最近邻在**同一条 SQL** 里（这才是"下推"，不是"取回来再筛"）。 */
    @Test
    void vectorQuery_pushesSourceFilterIntoSql() {
        search.byVector("冷战筑墙怎么办", 25);

        String sql = capturedSql();
        assertHasSafeFilter(sql);
        assertTrue(sql.contains("ORDER BY embedding <=>"), "仍必须是 ANN 查询：" + sql);
        assertTrue(sql.contains("LIMIT"), sql);
    }

    /** 关键词通道同样要过滤 —— 实测它命中的记忆占比不低（「沟通」95/174 = 55%）。 */
    @Test
    void keywordQuery_alsoAppliesSourceFilter() {
        search.byKeyword(List.of("冷战", "沟通"), 25);

        String sql = capturedSql();
        assertHasSafeFilter(sql);
        assertTrue(sql.contains("content LIKE"), "关键词通道仍是 LIKE 打分：" + sql);
    }

    /** 关键词为空时不查库（与改造前一致，避免空条件全表扫）。 */
    @Test
    void keywordQuery_emptyWords_skipsDatabase() {
        assertEquals(List.of(), search.byKeyword(List.of(), 25));
        assertEquals(List.of(), search.byKeyword(null, 25));
        verifyNoInteractions(pg);
    }

    /**
     * 载荷契约（**ADR-46 的回归点**）：正文必须来自 {@code content} 列，id 必须沿用库里的 id。
     *
     * <p>原实现写的是 {@code new Document(rs.getString("id"), new HashMap<>())}，
     * 而 Spring AI 的两参构造是 {@code (text, metadata)} —— 于是
     * <b>正文变成了 UUID 字符串</b>、<b>id 每次调用随机生成</b>，且两件事都不报错：
     * 检索条数正常、日志正常，只有重排（被喂 UUID → MRR 0.82 → 0.28）
     * 和"注入给模型的知识"（变成 UUID）会坏掉。</p>
     *
     * <p>这道断言守的是**返回对象的字段**，不是条数、也不是 SQL 文本 ——
     * 前面三条测的是"SQL 对不对"，测不出"解析出来的对象对不对"。</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void rowMapper_carriesContentAsText_andKeepsDatabaseId() throws Exception {
        String dbId = "f6e42cf9-2b5d-47d5-bea1-08b7c1ade20c";
        String content = "煤气灯效应的三个典型特征：否认、转移、孤立。";
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        when(rs.getString("id")).thenReturn(dbId);
        when(rs.getString("content")).thenReturn(content);
        when(rs.getString("metadata")).thenReturn("{\"chunk\":\"overlap\",\"chunk_index\":3}");

        search.byVector("煤气灯效应", 8);
        ArgumentCaptor<RowMapper<Document>> mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(pg).query(anyString(), mapper.capture(), any(Object[].class));
        Document d = mapper.getValue().mapRow(rs, 0);

        assertEquals(content, d.getText(), "正文必须来自 content 列（两参构造会把 id 当正文）");
        assertEquals(dbId, d.getId(), "id 必须沿用库里的 id（两参构造会随机生成新 id）");
        assertEquals("overlap", d.getMetadata().get("chunk"));
        assertEquals(3, ((Number) d.getMetadata().get("chunk_index")).intValue());
    }
}
