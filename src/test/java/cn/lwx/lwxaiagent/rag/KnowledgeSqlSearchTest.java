package cn.lwx.lwxaiagent.rag;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
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
}
