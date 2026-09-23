package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.config.PgvectorProperties;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.rag.rerank.RerankProperties;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 守<strong>候选获取与源过滤的顺序</strong>：过滤必须在**截断到 top-k 之前**发生。
 *
 * <p>为什么要有这个测试（ADR-40）：知识检索的向量通道与用户记忆**共用同一个索引**，
 * 而记忆块数量远多于知识块（实测某查询向量 top-24 里 memory=20 / knowledge=4）。
 * 若先截断再过滤，记忆会白占候选位，知识块被挤到个位数 ——
 * 甚至会 ≤ postprocessor 的 `topK` 而**让重排被静默跳过**（聊天链路上 Rerank call 0 次）。</p>
 *
 * <p>这个缺陷的隐蔽之处：**过滤本身是对的**（该滤掉的是滤掉了），
 * 错的是它与截断的**先后**。单看返回结果"没有记忆块"完全正常，只有看数量才发现少了。</p>
 *
 * <p>测试技巧：查询用一个**单字符**（如 {@code "x"}），
 * 使 {@code extractKeywords()} 返回空 → 关键词通道直接短路（不触碰数据库），
 * 从而把 hybrid 融合路径压成"只有向量通道"，可用 mock 完全控制候选。</p>
 */
class ParentChildDocumentRetrieverTest {

    private VectorStore vectorStore;
    private RerankProperties rerank;
    private PgvectorProperties pg;

    @BeforeEach
    void setup() {
        vectorStore = mock(VectorStore.class);
        rerank = new RerankProperties();          // 默认 enabled=false / mode=local / topN=20 / topK=5
        pg = new PgvectorProperties();
        pg.setUrl("jdbc:postgresql://127.0.0.1:5432/postgres");
        pg.setUsername("postgres");
        pg.setPassword("123456");
        pg.setDriverClassName("org.postgresql.Driver");
    }

    private ParentChildDocumentRetriever newRetriever(boolean hybrid, int topK) {
        return new ParentChildDocumentRetriever(vectorStore, rerank, pg,
                mock(EmbeddingModel.class), hybrid, /* logScore */ false, topK,
                new AiTelemetry(Tracer.NOOP));
    }

    private static Document memory(String id) {
        return new Document(id, "记忆片段 " + id, Map.of("source", "memory", "userId", "u1"));
    }

    private static Document knowledge(String id) {
        // 知识块的 metadata **没有 source 字段**（这是关键：过滤谓词必须能放行它们）
        return new Document(id, "知识片段 " + id, Map.of("filename", id + ".md"));
    }

    /** 4 条记忆在前、6 条知识块在后的候选序列——模拟"记忆把前排占满"的真实分布。 */
    private List<Document> memoryHeavyCandidates() {
        List<Document> list = new ArrayList<>();
        list.add(memory("m1"));
        list.add(memory("m2"));
        list.add(memory("m3"));
        list.add(memory("m4"));
        for (int i = 1; i <= 6; i++) list.add(knowledge("k" + i));
        return list;
    }

    /**
     * 核心断言：候选里 4 条记忆 + 6 条知识块、topK=5 时，必须返回 **5 条知识块**。
     *
     * <p>若把过滤挪到截断之后，融合先截到 5 条（=前 4 条记忆 + k1），过滤后只剩 **1 条** → 本测试失败。</p>
     */
    @Test
    void memoryBlocks_doNotConsumeTopKSlots_hybrid() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ true, /* topK */ 5);

        List<Document> out = retriever.retrieve(new Query("x"));

        assertEquals(5, out.size(),
                "topK=5 就必须给出 5 条知识块；少于 5 说明记忆白占了候选位（过滤发生在截断之后）");
        assertTrue(out.stream().allMatch(d -> d.getMetadata().get("source") == null),
                "返回里不允许混入记忆/技能块");
        // 顺序也要对：应当是得分最高的前 5 条知识块
        assertEquals(List.of("k1", "k2", "k3", "k4", "k5"),
                out.stream().map(Document::getId).toList());
    }

    /** 非 hybrid 兜底路径同样是"过取 → 过滤 → 截断"，不能被记忆挤掉。 */
    @Test
    void memoryBlocks_doNotConsumeTopKSlots_nonHybrid() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ false, /* topK */ 5);

        List<Document> out = retriever.retrieve(new Query("x"));

        assertEquals(5, out.size());
        assertTrue(out.stream().allMatch(d -> d.getMetadata().get("source") == null));
        // 兜底路径必须**过取 ×3**，否则记忆排满前排时知识块根本进不了候选
        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(cap.capture());
        assertEquals(15, cap.getValue().getTopK(),
                "非 hybrid 路径应过取 topK*3 再过滤截断（否则记忆挤占时知识块为 0）");
    }

    /** 扩窗条件必须覆盖**任一生效的**重排模式，而不是只认 llm。 */
    @Test
    void rerankActive_remoteMode_alsoWidensWindowToTopN() {
        rerank.setEnabled(true);
        rerank.setMode("remote");      // ← 回归点：原实现只认 "llm"
        rerank.setTopN(20);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ true, /* topK */ 5);

        retriever.retrieve(new Query("x"));

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(cap.capture());
        assertEquals(60, cap.getValue().getTopK(),
                "remote 生效时应扩窗到 topN(=20)，粗召回再 ×3 = 60；拿不到扩窗会让重排没东西可排");
    }

    @Test
    void rerankActive_llmMode_alsoWidensWindow() {
        rerank.setEnabled(true);
        rerank.setMode("llm");
        rerank.setTopN(20);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(true, 5);

        retriever.retrieve(new Query("x"));

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(cap.capture());
        assertEquals(60, cap.getValue().getTopK());
    }

    /** 重排关闭时不扩窗：k 仍等于 topK，避免"没开重排却白白多取"。 */
    @Test
    void rerankOff_doesNotWiden() {
        rerank.setEnabled(false);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(/* hybrid */ true, /* topK */ 5);

        retriever.retrieve(new Query("x"));

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(cap.capture());
        assertEquals(15, cap.getValue().getTopK(), "未开重排时应取 topK*3 = 15");
    }

    /** mode=off 也算未生效（isActive() = enabled && mode != off）。 */
    @Test
    void rerankEnabledButModeOff_doesNotWiden() {
        rerank.setEnabled(true);
        rerank.setMode("off");
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(memoryHeavyCandidates());
        ParentChildDocumentRetriever retriever = newRetriever(true, 5);

        retriever.retrieve(new Query("x"));

        ArgumentCaptor<SearchRequest> cap = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(cap.capture());
        assertEquals(15, cap.getValue().getTopK());
    }
}
