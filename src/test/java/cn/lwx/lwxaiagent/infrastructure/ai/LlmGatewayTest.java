package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
        // 空回复防御（LlmGateway.isEmptyResponse）要求 generation 带真实 content，
        // 否则 mock 的"成功响应"会被误判为空回复而触发重试/降级
        Generation generation = new Generation(new AssistantMessage(text));
        return ChatResponse.builder().metadata(meta).generations(java.util.List.of(generation)).build();
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

    @Test
    void fourHundredError_doesNotRetry() {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        RuntimeException badRequest = new org.springframework.web.reactive.function.client.WebClientResponseException(
                "bad request", org.springframework.http.HttpStatusCode.valueOf(400), "Bad Request",
                null, null, null, null);
        when(primary.call(any(Prompt.class))).thenThrow(badRequest);

        LlmGatewayProperties p = props();
        p.setFallbackEnabled(false); // 4xx 直接失败（重试无意义）
        LlmGateway gateway = new LlmGateway(primary, fallback, p, new SimpleMeterRegistry());
        // fallback 关闭时 4xx 直接失败包装为 BizException（不进入无意义重试）
        assertThrows(cn.lwx.lwxaiagent.common.BizException.class, () -> gateway.call(new Prompt("hi")));
        verify(primary, times(1)).call(any(Prompt.class)); // 只调一次，未重试
    }

    @Test
    void rateLimit_errorIsRetriedWithRetryAfter() throws Exception {
        ChatModel primary = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("Retry-After", "1");
        org.springframework.web.reactive.function.client.WebClientResponseException tooMany =
                new org.springframework.web.reactive.function.client.WebClientResponseException(
                        "rate limited", org.springframework.http.HttpStatusCode.valueOf(429), "Too Many Requests",
                        headers, null, null, null);
        when(primary.call(any(Prompt.class)))
                .thenThrow(tooMany)
                .thenReturn(response("ok", 10, 5)); // 第二次成功

        LlmGateway gateway = new LlmGateway(primary, fallback, props(), new SimpleMeterRegistry());
        gateway.call(new Prompt("hi")); // 429 → 尊重 Retry-After(1s) 重试 → 成功
        verify(primary, times(2)).call(any(Prompt.class));
    }
}
