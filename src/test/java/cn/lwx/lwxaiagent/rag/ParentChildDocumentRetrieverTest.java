package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.rag.rerank.RerankProperties;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 守<strong>候选获取与源过滤的顺序/窗口</strong>。
 *
 * <p>ADR-40 修的是"过滤必须在截断之前"（当时向量通道无过滤，靠 Java 层补救）。
 * <b>2026-09-24（ADR-43）后源过滤已下推到 SQL</b>（见 {@link KnowledgeSqlSearchTest}），
 * 所以本类现在的职责收窄为两件：</p>
 * <ol>
 *   <li><b>兜底仍在</b>：即便某条通道（现在或将来的新通道）漏了过滤，Java 层仍不放记忆进候选；</li>
 *   <li><b>扩窗条件</b>：任一生效的 rerank 模式都要把粗召回窗口放大到 {@code topN}。</li>
 * </ol>
 *
 * <p>测试技巧：查询用一个**单字符**（如 {@code "x"}），使 {@code extractKeywords()} 返回空
 * → 关键词通道短路，从而把 hybrid 融合压成"只有向量通道"，可用 mock 完全控制候选。</p>
 */
class ParentChildDocumentRetrieverTest {

    private KnowledgeSqlSearch sqlSearch;
    private RerankProperties rerank;

    @BeforeEach
    void setup() {
        sqlSearch = mock(KnowledgeSqlSearch.class);
        rerank = new RerankProperties();          // 默认 enabled=false / mode=local / topN=20 / topK=5
    }

    private ParentChildDocumentRetriever newRetriever(boolean hybrid, int topK) {
        return new ParentChildDocumentRetriever(rerank, sqlSearch, hybrid, /* logScore */ false, topK,
                new AiTelemetry(Tracer.NOOP));
    }

    private static Document memory(String id) {
        return new Document(id, "记忆片段 " + id, Map.of("source", "memory", "userId", "u1"));
    }

    private static Document knowledge(String id) {
        // 知识块的 metadata **没有 source 字段**（关键：过滤谓词必须放行它们）
        return new Document(id, "知识片段 " + id, Map.of("filename", id + ".md"));
    }

    /** 4 条记忆在前、6 条知识块在后的候选序列——模拟"记忆把前排占满"的真实分布。 */
    private List<Document> memoryHeavyCandidates() {
        List<Document> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) list.add(memory("m" + i));
        for (int i = 1; i <= 6; i++) list.add(knowledge("k" + i));
        return list;
    }

    /**
     * 兜底过滤（hybrid）：即使某条通道把记忆也带了回来，也不能进候选，更不能占掉 topK 位。
     *
     * <p>若把过滤挪到截断之后，融合先截 5 条（=4 条记忆 + k1），过滤后只剩 **1 条** → 失败。</p>
     */
    @Test
    void memoryInCandidates_stillFilteredBeforeTruncation_hybrid() {
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ true, /* topK */ 5);

        List<Document> out = retriever.retrieve(new Query("x"));

        assertEquals(5, out.size(),
                "topK=5 就必须给出 5 条知识块；少于 5 说明记忆白占了候选位（过滤发生在截断之后）");
        assertTrue(out.stream().allMatch(d -> d.getMetadata().get("source") == null),
                "返回里不允许混入记忆/技能块");
        assertEquals(List.of("k1", "k2", "k3", "k4", "k5"),
                out.stream().map(Document::getId).toList(), "顺序应是得分最高的前 5 条知识块");
    }

    /** 兜底过滤（非 hybrid 路径）同样不能放记忆进候选。 */
    @Test
    void memoryInCandidates_stillFiltered_nonHybrid() {
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ false, /* topK */ 5);

        List<Document> out = retriever.retrieve(new Query("x"));

        assertTrue(out.stream().allMatch(d -> d.getMetadata().get("source") == null),
                "返回里不允许混入记忆/技能块");
        assertEquals(6, out.size(), "6 条知识块都该留下（SQL 已过滤，此处只是兜底）");
    }

    /** 扩窗条件必须覆盖**任一生效的**重排模式，而不是只认 llm。 */
    @Test
    void rerankActive_remoteMode_alsoWidensWindowToTopN() {
        rerank.setEnabled(true);
        rerank.setMode("remote");      // ← 回归点：原实现只认 "llm"
        rerank.setTopN(20);
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ true, /* topK */ 5);

        retriever.retrieve(new Query("x"));

        assertEquals(60, capturedVectorWindow(),
                "remote 生效时应扩窗到 topN(=20)，粗召回再 ×3 = 60；拿不到扩窗会让重排没东西可排");
    }

    @Test
    void rerankActive_llmMode_alsoWidensWindow() {
        rerank.setEnabled(true);
        rerank.setMode("llm");
        rerank.setTopN(20);
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());

        newRetriever(/* hybrid */ true, /* topK */ 5).retrieve(new Query("x"));

        assertEquals(60, capturedVectorWindow());
    }

    /** 重排关闭时不扩窗：k 仍等于 topK，避免"没开重排却白白多取"。 */
    @Test
    void rerankOff_doesNotWiden() {
        rerank.setEnabled(false);
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());

        newRetriever(/* hybrid */ true, /* topK */ 5).retrieve(new Query("x"));

        assertEquals(15, capturedVectorWindow(), "未开重排时应取 topK*3 = 15");
    }

    /** mode=off 也算未生效（isActive() = enabled && mode != off）。 */
    @Test
    void rerankEnabledButModeOff_doesNotWiden() {
        rerank.setEnabled(true);
        rerank.setMode("off");
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());

        newRetriever(/* hybrid */ true, /* topK */ 5).retrieve(new Query("x"));

        assertEquals(15, capturedVectorWindow());
    }

    /**
     * 非 hybrid 路径**不再过取 ×3**（2026-09-24 的变化，ADR-43）：
     * 旧实现必须先取 k*3 再在 Java 层过滤，是因为候选里混着记忆；
     * 现在 SQL 已保证只回知识块，取 k 条就是 k 条 —— 过取反而白付 ANN 代价。
     */
    @Test
    void nonHybrid_takesExactlyK_noOverFetch() {
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(memoryHeavyCandidates());

        newRetriever(/* hybrid */ false, /* topK */ 8).retrieve(new Query("x"));

        assertEquals(8, capturedVectorWindow(), "非 hybrid 无融合需求 → 直接取 k（不再 ×3）");
    }

    /** 捕获向量通道被要求的窗口大小（byVector 的第二个参数）。 */
    private int capturedVectorWindow() {
        ArgumentCaptor<Integer> k = ArgumentCaptor.forClass(Integer.class);
        verify(sqlSearch).byVector(anyString(), k.capture());
        return k.getValue();
    }
}
