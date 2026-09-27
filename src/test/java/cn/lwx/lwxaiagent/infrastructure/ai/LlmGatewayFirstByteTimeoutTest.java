package cn.lwx.lwxaiagent.infrastructure.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-49：流式首字节超时必须由 {@code first-byte-timeout-ms} 决定，
 * <b>不能</b>复用 {@code attempt-timeout-ms}。
 *
 * <p>为什么锁这条：这两个值语义不同 —— attempt-timeout 是<b>同步整调用</b>的预算
 * （实测生成 300~500 tok 要 10~17s），首字节上限是「模型有没有起步」。
 * 共用 45s 时，真实故障要用户白等 45s 才进降级；而把它调到 10s 又会误杀正常的同步长回答。</p>
 */
class LlmGatewayFirstByteTimeoutTest {

    private ChatModel primary;
    private LlmGatewayProperties props;
    private SimpleMeterRegistry meters;
    private LlmGateway gateway;

    @BeforeEach
    void setup() {
        primary = mock(ChatModel.class);
        props = new LlmGatewayProperties();
        props.getRetry().setBackoffMs(1);
        props.getRetry().setJitter(0);
        props.getRetry().setMaxAttempts(1);
        props.setDegradeEnabled(false);           // 不降级：本例只测超时是否按 first-byte 生效
        props.setAttemptTimeoutMs(5000);          // 故意给一个大值：若代码仍复用它，用例会超时失败
        props.setFirstByteTimeoutMs(200);
        props.setTotalTimeoutMs(5000);
        props.setStreamIdleTimeoutMs(100);
        meters = new SimpleMeterRegistry();
        gateway = new LlmGateway(primary, null, props, meters);
    }

    @AfterEach
    void cleanup() {
        if (gateway != null) gateway.close();
        meters.close();
        Thread.interrupted();
    }

    @Test
    @DisplayName("模型一直不出首字节 → 按 first-byte-timeout(200ms) 失败，而不是等 attempt-timeout(5s)")
    void firstByteTimeoutIsIndependentOfAttemptTimeout() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.never());

        long t0 = System.nanoTime();
        assertThrows(Exception.class, () -> gateway.stream(new Prompt("t")).blockLast(Duration.ofSeconds(3)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 2000,
                "首字节超时应由 first-byte-timeout(200ms) 决定，实际耗时 " + elapsedMs + "ms");
        assertTrue(meters.find("llm.timeout.stream").tag("outcome", "first_byte").counter() != null,
                "应记录 kind=first_byte 的流式超时");
    }

    @Test
    @DisplayName("起步后长时间空窗 → 记 stream_idle，不记 first_byte（两类故障不能混）")
    void idleAfterFirstByteIsNotCountedAsFirstByteTimeout() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("hi"))
                .concatWith(Flux.never()));
        assertThrows(Exception.class, () -> gateway.stream(new Prompt("t")).blockLast(Duration.ofSeconds(3)));

        assertTrue(meters.find("llm.timeout.stream").tag("outcome", "stream_idle").counter() != null,
                "应记录 kind=stream_idle");
        assertTrue(meters.find("llm.timeout.stream").tag("outcome", "first_byte").counter() == null,
                "已吐过内容就不该记 first_byte");
    }
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                new ChatResponseMetadata());
    }

}
