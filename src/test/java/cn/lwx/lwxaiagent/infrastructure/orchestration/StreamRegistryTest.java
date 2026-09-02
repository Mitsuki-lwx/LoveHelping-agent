package cn.lwx.lwxaiagent.infrastructure.orchestration;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * StreamSink 真流式转发单测（2026-09-02）：
 * 普通文本实时转发、advice marker 剥离、marker 跨块安全、取消防护。
 */
class StreamRegistryTest {

    private static final String MARKER = "@@ADVICE@@";

    /** 用 mock FluxSink + doAnswer 收集 next 调用，避免手写接口全部方法 */
    private List<String> recordingSink(FluxSink<String> raw) {
        List<String> emitted = new ArrayList<>();
        doAnswer(inv -> { emitted.add(inv.getArgument(0)); return raw; })
                .when(raw).next(anyString());
        return emitted;
    }

    @Test
    void plainTextIsFullyEmittedAfterFlush() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.append("你好，我是爱情顾问。");
        s.append("很高兴认识你。");
        s.flush();
        assertEquals("你好，我是爱情顾问。很高兴认识你。", String.join("", emitted));
        assertTrue(s.streamed());
    }

    @Test
    void adviceMarkerTextBeforeIsEmittedPayloadNot() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.enableMarkerStripping();
        s.append("这是给你的回复。");
        s.append(MARKER + "{\"tiers\":[{\"name\":\"safe\"}]}");
        s.flush();
        assertEquals("这是给你的回复。", String.join("", emitted));
        assertTrue(s.streamed());
    }

    @Test
    void markerSpanningChunksIsStillStripped() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.enableMarkerStripping();
        s.append("回复正文第一段");
        s.append("回复正文第二段。@");          // marker 开头散落在此块
        s.append("@ADVICE@@{\"tiers\":[]}");    // 跨块拼出完整 marker
        s.flush();
        assertEquals("回复正文第一段回复正文第二段。", String.join("", emitted));
    }

    @Test
    void cancelledSinkStopsPushing() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.cancel();
        s.append("不应被推送");
        s.flush();
        assertEquals(0, emitted.size());
        assertFalse(s.streamed());
    }

    @Test
    void mockSinkNextExceptionIsSwallowed() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("sink cancelled"))
                .when(raw).next(anyString());
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.append("触发一次异常");
        s.append("之后应被静默");
        s.flush();
        assertFalse(s.streamed());
    }

    @Test
    void reasoningDiscardedByDefault() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "discard");
        s.appendReasoning("让我想想……这个用户可能是焦虑型依恋。");
        s.append("正文回答。");
        s.flush();
        assertEquals("正文回答。", String.join("", emitted)); // 思考不进用户流
        assertTrue(s.streamed());
    }

    @Test
    void reasoningStreamModePrefixed() {
        @SuppressWarnings("unchecked")
        FluxSink<String> raw = mock(FluxSink.class);
        List<String> emitted = recordingSink(raw);
        StreamRegistry.StreamSink s = new StreamRegistry.StreamSink(raw, "stream");
        s.appendReasoning("先判断意图");
        s.append("正文。");
        s.flush();
        String out = String.join("", emitted);
        assertEquals("\u00a7R\u00a7先判断意图正文。", out); // §R§ 前缀独立于正文
    }
}
