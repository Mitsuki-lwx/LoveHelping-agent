package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LlmGateway 单元测试（ADR-7）：重试、降级、计量。
 */
class LlmGatewayTest {

    private LlmGatewayProperties props() {
        LlmGatewayProperties p = new LlmGatewayProperties();
        p.getRetry().setMaxAttempts(3);
        p.getRetry().setBackoffMs(1); // 测试用短退避
        p.setFallbackEnabled(true);
        return p;
    }

    private ChatResponse response(String text, int prompt, int completion) {
        Usage usage = mock(Usage.class);
        doReturn(prompt).when(usage).getPromptTokens();
        doReturn(completion).when(usage).getCompletionTokens();
        ChatResponseMetadata meta = ChatResponseMetadata.builder().usage(usage).build();
        return ChatResponse.builder().metadata(meta).generations(java.util.List.of()).build();
    }

    @Test
    void primarySuccess_usesPrimary_only() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse expected = response("ok", 10, 5);
        when(primary.call(any(Prompt.class))).thenReturn(expected);

        LlmGateway gateway = new LlmGateway(primary, fallback, props(), new SimpleMeterRegistry());
        ChatResponse result = gateway.call(new Prompt("hi"));

        assertSame(expected, result);
        verify(primary, times(1)).call(any(Prompt.class));
        verify(fallback, never()).call(any(Prompt.class));
    }

    @Test
    void primaryFails_retriesThenFallsBack() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse expected = response("fallback", 3, 1);
        // 主模型始终失败 → 重试 maxAttempts(3) 次后切备
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));
        when(fallback.call(any(Prompt.class))).thenReturn(expected);

        LlmGateway gateway = new LlmGateway(primary, fallback, props(), new SimpleMeterRegistry());
        ChatResponse result = gateway.call(new Prompt("hi"));

        assertSame(expected, result);
        verify(primary, times(3)).call(any(Prompt.class)); // 含首次共 3 次
        verify(fallback, times(1)).call(any(Prompt.class));
    }

    @Test
    void bothFail_throwsBizException() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));
        when(fallback.call(any(Prompt.class))).thenThrow(new RuntimeException("fallback down"));

        LlmGateway gateway = new LlmGateway(primary, fallback, props(), new SimpleMeterRegistry());
        assertThrows(BizException.class, () -> gateway.call(new Prompt("hi")));
    }

    @Test
    void fallbackDisabled_primaryFailurePropagates() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("primary down"));

        LlmGatewayProperties p = props();
        p.setFallbackEnabled(false);
        LlmGateway gateway = new LlmGateway(primary, fallback, p, new SimpleMeterRegistry());

        assertThrows(RuntimeException.class, () -> gateway.call(new Prompt("hi")));
        verify(fallback, never()).call(any(Prompt.class));
    }

    @Test
    void usage_recordedToMeterRegistry() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        ChatResponse resp = response("ok", 120, 80);
        when(primary.call(any(Prompt.class))).thenReturn(resp);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGateway gateway = new LlmGateway(primary, fallback, props(), registry);
        gateway.call(new Prompt("hi"));

        double promptTokens = registry.counter("llm.tokens", "type", "prompt").count();
        double completionTokens = registry.counter("llm.tokens", "type", "completion").count();
        assertEquals(120.0, promptTokens);
        assertEquals(80.0, completionTokens);
    }
}
