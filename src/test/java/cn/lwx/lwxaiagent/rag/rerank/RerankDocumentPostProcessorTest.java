package cn.lwx.lwxaiagent.rag.rerank;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 守<strong>接线层</strong>：把「模式 → 引擎」的映射钉死。
 *
 * <p>为什么必须单独有这一个测试类（2026-09-21）：`LocalDocumentRerankerTest` 把 remote 模式
 * 测得很全（请求体带 model、带 Bearer 鉴权、缺 key 时构造即抛错），但那是**类自身**的行为。
 * 后处理器里原本写的是 {@code "local".equals(mode) ? local : llm} ——
 * 于是 {@code mode=remote} 被**静默路由到 LLM 重排**，硅基流动 {@code /v1/rerank}
 * 一次都没被调用，而全部单测依然是绿的。**接线无人守，只有实测才暴露。**</p>
 *
 * <p>教训归类：这不是"实现写错了"，是"实现对了但没接上"——
 * 单测覆盖了组件，没覆盖组件之间的连线。</p>
 */
class RerankDocumentPostProcessorTest {

    private RerankProperties props;
    private LlmDocumentReranker llm;
    private LocalDocumentReranker local;
    private SimpleMeterRegistry meters;
    private RerankDocumentPostProcessor processor;

    @BeforeEach
    void setup() {
        props = new RerankProperties();
        props.setEnabled(true);
        props.setMode("local");
        props.setTopK(3);
        llm = mock(LlmDocumentReranker.class);
        local = mock(LocalDocumentReranker.class);
        meters = new SimpleMeterRegistry();
        processor = new RerankDocumentPostProcessor(props, llm, local, meters, new AiTelemetry(Tracer.NOOP));
    }

    @AfterEach
    void cleanup() {
        meters.close();
    }

    private static List<Document> docs(int n) {
        List<Document> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Document("doc-" + i));
        return out;
    }

    private static List<Document> head(List<Document> d, int k) {
        return new ArrayList<>(d.subList(0, k));
    }

    /** remote（硅基流动 8B）必须走 HTTP 重排器，**绝不能**落到 LLM 打分。 */
    @Test
    void remoteMode_usesHttpReranker_notLlm() {
        props.setMode("remote");
        List<Document> candidates = docs(8);
        when(local.rerank(anyString(), anyList(), eq(3))).thenReturn(head(candidates, 3));

        List<Document> out = processor.process(new Query("我们冷战了怎么办"), candidates);

        assertEquals(3, out.size(), "重排后应被压到 topK");
        verify(local).rerank(anyString(), anyList(), eq(3));
        verify(llm, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void localMode_usesHttpReranker() {
        props.setMode("local");
        List<Document> candidates = docs(8);
        when(local.rerank(anyString(), anyList(), eq(3))).thenReturn(head(candidates, 3));

        processor.process(new Query("q"), candidates);

        verify(local).rerank(anyString(), anyList(), eq(3));
        verify(llm, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void llmMode_usesLlmReranker() {
        props.setMode("llm");
        List<Document> candidates = docs(8);
        when(llm.rerank(anyString(), anyList(), eq(3))).thenReturn(head(candidates, 3));

        processor.process(new Query("q"), candidates);

        verify(llm).rerank(anyString(), anyList(), eq(3));
        verify(local, never()).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void disabled_skipsRerankEntirely() {
        props.setEnabled(false);
        List<Document> candidates = docs(8);

        List<Document> out = processor.process(new Query("q"), candidates);

        assertEquals(8, out.size(), "未启用时一个候选都不许丢");
        verifyNoInteractions(local, llm);
    }

    /** 引擎故障必须降级为原顺序前 K，且**有界**（不把候选原样透出）。 */
    @Test
    void engineFailure_fallsBackToOriginalOrder_boundedToTopK() {
        props.setMode("remote");
        List<Document> candidates = docs(8);
        when(local.rerank(anyString(), anyList(), eq(3))).thenThrow(new IllegalStateException("upstream down"));

        List<Document> out = processor.process(new Query("q"), candidates);

        assertEquals(3, out.size());
        assertSame(candidates.get(0), out.get(0), "降级应保持原顺序");
    }

    /** 候选数不超过 topK 时不必发请求（成本守卫）。 */
    @Test
    void candidatesNotMoreThanTopK_skipsEngineCall() {
        props.setMode("remote");
        List<Document> candidates = docs(3);

        List<Document> out = processor.process(new Query("q"), candidates);

        assertEquals(3, out.size());
        verifyNoInteractions(local, llm);
    }
}
