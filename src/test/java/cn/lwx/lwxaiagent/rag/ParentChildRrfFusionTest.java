package cn.lwx.lwxaiagent.rag;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import cn.lwx.lwxaiagent.rag.rerank.RerankProperties;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <h3>RRF 融合（`docs/09` §2 点名的「RRF 融合排序稳定性」）</h3>
 *
 * <p>混合召回 = 向量 topN + 关键词 topN → RRF 融合。这里钉四条**静默失效**风险最高的契约：</p>
 * <ol>
 *   <li><b>去重</b>：同一条块被两个通道都召回时只能占**一个**位（否则它既占位又被重复喂给重排）；</li>
 *   <li><b>双通道命中要加权</b>：RRF 的意义就在于此（1/(k+rank) 相加），不加权等于混合退化成并集；</li>
 *   <li>⛔ **先过滤、后截断**（ADR-40 的旧 bug：顺序反了会让记忆块白占候选位，
 *       极端情况下重排在聊天链路上被**静默跳过** —— 实测 hits=5 ≤ topK=5，Rerank call 0 次）；</li>
 *   <li>关键词通道也要**过知识库过滤**（记忆/进化产物不许从旁路溜进来）。</li>
 * </ol>
 */
@DisplayName("ParentChildDocumentRetriever：RRF 融合契约")
class ParentChildRrfFusionTest {

    private KnowledgeSqlSearch sqlSearch;
    private RerankProperties rerank;

    @BeforeEach
    void setUp() {
        sqlSearch = mock(KnowledgeSqlSearch.class);
        rerank = new RerankProperties();
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(List.of());
        when(sqlSearch.byKeyword(anyList(), anyInt())).thenReturn(List.of());
    }

    private ParentChildDocumentRetriever retriever(int topK) {
        return new ParentChildDocumentRetriever(rerank, sqlSearch, /* hybrid */ true,
                /* logScore */ false, topK, new AiTelemetry(Tracer.NOOP));
    }

    private static Document knowledge(String id) {
        return new Document(id, "知识片段 " + id, Map.of("filename", id + ".md"));
    }

    private static Document memory(String id) {
        return new Document(id, "记忆片段 " + id, Map.of("source", "memory"));
    }

    private List<String> runAndCollectIds(int topK) {
        return retriever(topK).retrieve(new Query("冷战筑墙怎么办")).stream().map(Document::getId).toList();
    }

    @Test
    @DisplayName("去重：两个通道都召回的同一条块，结果里只出现一次")
    void same_doc_from_both_channels_appears_once() {
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(List.of(knowledge("k1"), knowledge("k2")));
        when(sqlSearch.byKeyword(anyList(), anyInt())).thenReturn(List.of(knowledge("k1"), knowledge("k3")));

        List<String> ids = runAndCollectIds(5);

        assertThat(ids).as("k1 被两个通道召回，也只能占一个位").containsExactly("k1", "k2", "k3");
    }

    @Test
    @DisplayName("RRF 加权：双通道命中的条块要排在「单通道同秩」之前")
    void doc_hit_by_both_channels_outranks_single_channel() {
        // 向量： a, b ；关键词： b, c
        // RRF：a=1/61，b=1/61+1/61（双通道），c=1/61 → 第一名必须是 b
        when(sqlSearch.byVector(anyString(), anyInt())).thenReturn(List.of(knowledge("a"), knowledge("b")));
        when(sqlSearch.byKeyword(anyList(), anyInt())).thenReturn(List.of(knowledge("b"), knowledge("c")));

        assertThat(runAndCollectIds(5).get(0))
                .as("不给双通道命中加权，混合召回就退化成并集")
                .isEqualTo("b");
    }

    @Test
    @DisplayName("⛔ 先过滤、后截断：记忆块不许占掉 topK 位（ADR-40 的旧 bug）")
    void filter_happens_before_truncation() {
        when(sqlSearch.byVector(anyString(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(memory("m1"), memory("m2"), memory("m3"), knowledge("k1"))));

        List<String> ids = runAndCollectIds(3);

        assertThat(ids)
                .as("若先截断到 3 条再过滤，结果会只剩 k1（甚至为空）—— 那会让重排被静默跳过")
                .containsExactly("k1");
    }

    @Test
    @DisplayName("关键词通道同样要过知识库过滤：记忆不许从旁路溜进来")
    void keyword_channel_is_also_filtered() {
        when(sqlSearch.byKeyword(anyList(), anyInt()))
                .thenReturn(new ArrayList<>(List.of(memory("m9"), knowledge("k9"))));

        assertThat(runAndCollectIds(5)).containsExactly("k9");
    }

    @Test
    @DisplayName("中文分词：关键词通道拿到的是**多个词**且不含单字（否则 LIKE 近乎空转）")
    void keyword_extraction_splits_chinese_and_drops_single_chars() {
        runAndCollectIds(5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cap = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(sqlSearch).byKeyword(cap.capture(), anyInt());

        List<String> kws = cap.getValue();
        assertThat(kws).as("中文无空格，整段当一个词 LIKE 会让关键词通道空转").isNotEmpty();
        assertThat(kws).allSatisfy(k -> assertThat(k.length())
                .as("单字命中面太大，必须丢掉：" + k)
                .isGreaterThanOrEqualTo(2));
        assertThat(kws).as("应切出「冷战」「筑墙」这类实体词").contains("冷战", "筑墙");
    }
}
