package cn.lwx.lwxaiagent.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <h1>DegradingDocumentRetriever 降级契约测试</h1>
 *
 * <p>固化的行为：检索失败必须降级为"无上下文"，不得把异常抛给 advisor
 * （否则整条对话以 5000「AI 服务暂时不可用」失败——2026-09-16 实测回归）。</p>
 */
@ExtendWith(MockitoExtension.class)
class DegradingDocumentRetrieverTest {

    @Mock
    private DocumentRetriever delegate;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private Counter degradedCounter;

    private DegradingDocumentRetriever retriever() {
        return new DegradingDocumentRetriever(delegate, meterRegistry);
    }

    /** 核心回归：检索抛异常时必须返回空列表，不得向上抛出。 */
    @Test
    void retrievalFailureMustDegradeToEmptyContext() {
        when(delegate.retrieve(any(Query.class)))
                .thenThrow(new RuntimeException("I/O error on POST request for \"https://dashscope.aliyuncs.com/...\": "
                        + "Remote host terminated the handshake"));

        List<Document> docs = assertDoesNotThrow(() -> retriever().retrieve(new Query("怎么约会聊天不冷场")));

        assertEquals(List.of(), docs, "检索不可用时必须降级为无上下文");
    }

    /** 降级必须可观测：计入 rag.retrieve.degraded。 */
    @Test
    void degradationMustBeCounted() {
        when(delegate.retrieve(any(Query.class))).thenThrow(new RuntimeException("boom"));
        when(meterRegistry.counter(DegradingDocumentRetriever.DEGRADED_METRIC)).thenReturn(degradedCounter);

        retriever().retrieve(new Query("问题"));

        verify(degradedCounter).increment();
    }

    /** 指标上报自身抛异常时，仍必须返回空列表（降级路径不可被二次异常击穿）。 */
    @Test
    void metricFailureMustNotDefeatDegradation() {
        when(delegate.retrieve(any(Query.class))).thenThrow(new RuntimeException("boom"));
        when(meterRegistry.counter(DegradingDocumentRetriever.DEGRADED_METRIC))
                .thenThrow(new IllegalStateException("registry closed"));

        List<Document> docs = assertDoesNotThrow(() -> retriever().retrieve(new Query("问题")));

        assertEquals(List.of(), docs);
    }

    /** 正常路径必须原样透传（零行为变更：不复制、不重排、不裁剪）。 */
    @Test
    void successfulRetrievalIsPassedThroughUnchanged() {
        List<Document> expected = List.of(new Document("父块正文"));
        when(delegate.retrieve(any(Query.class))).thenReturn(expected);

        List<Document> actual = retriever().retrieve(new Query("问题"));

        assertSame(expected, actual, "成功路径必须原样返回委托结果");
    }
}
