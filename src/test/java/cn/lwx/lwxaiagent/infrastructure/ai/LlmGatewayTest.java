package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmGatewayTest {
    ChatModel primary, fallback;
    LlmGatewayProperties props;
    SimpleMeterRegistry meters;
    LlmGateway gateway;
    @BeforeEach void setup() {
        primary = mock(ChatModel.class); fallback = mock(ChatModel.class);
        props = new LlmGatewayProperties(); props.getRetry().setBackoffMs(1); props.getRetry().setJitter(0);
        props.setAttemptTimeoutMs(500); props.setTotalTimeoutMs(2000); props.setStreamIdleTimeoutMs(100);
        meters = new SimpleMeterRegistry();
    }
    LlmGateway create() { gateway = new LlmGateway(primary, fallback, props, meters); return gateway; }
    @AfterEach void cleanup() { if (gateway != null) gateway.close(); meters.close(); Thread.interrupted(); }
    static ChatResponse response(String text) {
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().model("mock-model").usage(new DefaultUsage(10, 5)).build()).build();
    }
    static WebClientResponseException http(int code) { return WebClientResponseException.create(code, "test", HttpHeaders.EMPTY, null, null); }

    @Test void primarySuccess() {
        var r = response("ok"); when(primary.call(any(Prompt.class))).thenReturn(r);
        assertSame(r, create().call(new Prompt("test"))); verifyNoInteractions(fallback);
        assertEquals(10, meters.get("llm.tokens").tags("provider", "primary", "type", "prompt").counter().count());
    }
    @Test void transientRetriesThenFallback() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503)); when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));
        assertEquals("backup", create().call(new Prompt("test")).getResult().getOutput().getText());
        verify(primary, times(3)).call(any(Prompt.class)); verify(fallback).call(any(Prompt.class));
    }
    @Test void unknownProgrammingErrorIsNotRetriedOrFailedOver() {
        when(primary.call(any(Prompt.class))).thenThrow(new IllegalArgumentException("invalid input"));
        assertThrows(BizException.class, () -> create().call(new Prompt("test")));
        verify(primary).call(any(Prompt.class)); verifyNoInteractions(fallback);
    }
    @Test void client400DoesNotRetryOrFallback() {
        when(primary.call(any(Prompt.class))).thenThrow(http(400));
        assertThrows(BizException.class, () -> create().call(new Prompt("test")));
        verify(primary).call(any(Prompt.class)); verifyNoInteractions(fallback);
    }
    @Test void disabledFallbackDoesNotCountPhantomFallback() {
        props.setFallbackEnabled(false); when(primary.call(any(Prompt.class))).thenThrow(http(503));
        assertThrows(BizException.class, () -> create().call(new Prompt("test")));
        assertNull(meters.find("llm.fallback").counter()); verifyNoInteractions(fallback);
    }
    @Test void interruptionDoesNotStartAnotherAttempt() {
        Thread.currentThread().interrupt();
        assertThrows(CancellationException.class, () -> create().call(new Prompt("test")));
        assertTrue(Thread.currentThread().isInterrupted()); verifyNoInteractions(primary, fallback);
    }
    @Test void emptySyncResponseRetries() {
        when(primary.call(any(Prompt.class))).thenReturn(response(""), response("ok"));
        assertEquals("ok", create().call(new Prompt("test")).getResult().getOutput().getText());
        verify(primary, times(2)).call(any(Prompt.class));
    }
    @Test void toolOnlyResponseIsValid() {
        var assistant = AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "search", "{}"))).build();
        when(primary.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(assistant))));
        assertFalse(create().call(new Prompt("test")).getResult().getOutput().getToolCalls().isEmpty());
        verify(primary).call(any(Prompt.class));
    }
    @Test void streamRetriesBeforeFirstChunk() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)), Flux.just(response("ok")));
        assertEquals(1, create().stream(new Prompt("test")).collectList().block(Duration.ofSeconds(3)).size());
        verify(primary, times(2)).stream(any(Prompt.class)); verifyNoInteractions(fallback);
    }
    @Test void partialStreamNeverReplaysOrSwitchesProvider() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("prefix")), Flux.error(http(503))));
        var seen = new java.util.ArrayList<String>();
        assertThrows(BizException.class, () -> create().stream(new Prompt("test"))
                .doOnNext(r -> seen.add(r.getResult().getOutput().getText())).blockLast());
        assertEquals(List.of("prefix"), seen); verify(primary).stream(any(Prompt.class)); verifyNoInteractions(fallback);
    }
    @Test void emptyStreamFailsOver() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.empty()); when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("backup")));
        assertEquals("backup", create().stream(new Prompt("test")).blockLast().getResult().getOutput().getText());
        verify(primary, times(3)).stream(any(Prompt.class));
    }
    @Test void cumulativeStreamUsageCountedOnce() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("a"), response("b"), response("c")));
        create().stream(new Prompt("test")).blockLast();
        assertEquals(10, meters.get("llm.tokens").tags("provider", "primary", "type", "prompt").counter().count());
    }
    @Test void cancellationReleasesStreamCapacity() {
        props.setMaxConcurrentCalls(1); when(primary.stream(any(Prompt.class))).thenReturn(Flux.never(), Flux.just(response("ok")));
        var g = create(); var subscription = g.stream(new Prompt("test")).subscribe(); subscription.dispose();
        assertNotNull(g.stream(new Prompt("next")).blockLast(Duration.ofSeconds(1)));
    }
    @Test void totalDeadlineTerminatesContinuouslyEmittingStream() {
        props.setTotalTimeoutMs(100); props.setFallbackEnabled(false);
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.interval(Duration.ofMillis(5)).map(i -> response("x")));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(BizException.class,
                () -> create().stream(new Prompt("test")).blockLast()));
        verify(primary).stream(any(Prompt.class));
    }
    @Test void hangingSyncWorkIsInterruptedAndBounded() throws Exception {
        props.setAttemptTimeoutMs(40); props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false); props.setMaxConcurrentCalls(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(primary.call(any(Prompt.class))).thenAnswer(i -> {
            try { new CountDownLatch(1).await(); return response("never"); }
            catch (InterruptedException e) { interrupted.countDown(); throw new CancellationException(); }
        });
        assertThrows(BizException.class, () -> create().call(new Prompt("test")));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }
    @Test void sharedRetryBudgetBoundsStorm() {
        props.getRetry().setBudgetPerMinute(2); props.getCircuit().setEnabled(false); props.setFallbackEnabled(false);
        when(primary.call(any(Prompt.class))).thenThrow(http(503)); var g = create();
        for (int i = 0; i < 10; i++) assertThrows(BizException.class, () -> g.call(new Prompt("test")));
        verify(primary, times(12)).call(any(Prompt.class));
    }
    @Test void circuitOpensAndRejectsWithoutCallingSupplier() {
        props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false);
        // ADR-32：滑窗失败率判定。2 个样本全部失败 = 100% >= 50% → 打开。
        props.getCircuit().setSlidingWindowSize(2);
        props.getCircuit().setMinimumNumberOfCalls(2);
        props.getCircuit().setFailureRateThreshold(0.5);
        when(primary.call(any(Prompt.class))).thenThrow(http(503)); var g = create();
        for (int i = 0; i < 5; i++) assertThrows(BizException.class, () -> g.call(new Prompt("test")));
        verify(primary, times(2)).call(any(Prompt.class));
    }

    /** S10 回归锁：厂商个位数~两成百分比的背景拒绝不得把熔断打开（旧连续计数实现在此会打开）。 */
    @Test void transientThrottleBurstDoesNotTripCircuit() {
        props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        when(primary.call(any(Prompt.class))).thenAnswer(i -> {
            if (calls.incrementAndGet() % 5 == 0) throw http(429);
            return response("ok");
        });
        var g = create();
        for (int i = 0; i < 60; i++) {
            try { g.call(new Prompt("test")); } catch (BizException expected) { /* 20% 背景拒绝 */ }
        }
        verify(primary, times(60)).call(any(Prompt.class)); // 60 次全部真正打到供应商，零次被熔断快速失败
        assertNull(meters.find("llm.circuit").counter(), "20% 背景拒绝不应触发熔断");
    }

    /** ADR-32：闸门遇限流乘性收缩、连续成功加性回升（厂商上限不固定，让闸门自己收敛）。 */
    @Test void adaptiveGateShrinksOnThrottleAndGrowsAfterSuccesses() {
        props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false); props.getCircuit().setEnabled(false);
        var g = create();
        assertEquals(24, limit());
        when(primary.call(any(Prompt.class))).thenThrow(http(429));
        assertThrows(BizException.class, () -> g.call(new Prompt("test")));
        assertEquals(16, limit(), "floor(24 * 0.7) = 16");
        when(primary.call(any(Prompt.class))).thenReturn(response("ok"));
        for (int i = 0; i < 20; i++) g.call(new Prompt("test"));
        assertEquals(17, limit(), "连续 20 次成功后加性回升 1");
    }

    /** 收缩有地板：厂商持续限流也不会把闸门压到自我饿死。 */
    @Test void adaptiveGateNeverShrinksBelowFloor() {
        props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false); props.getCircuit().setEnabled(false);
        when(primary.call(any(Prompt.class))).thenThrow(http(429));
        var g = create();
        for (int i = 0; i < 20; i++) assertThrows(BizException.class, () -> g.call(new Prompt("test")));
        assertEquals(4, limit(), "地板 = adaptive.min-concurrent-calls");
    }

    private double limit() {
        return meters.get("llm.permits.limit").gauge().value();
    }

    /** ADR-32：闸门收缩/回升必须广播给准入层，否则两层闸门会不一致。 */
    @Test void adaptiveChangeBroadcastsAdmissionCeiling() {
        props.getRetry().setMaxAttempts(1); props.setFallbackEnabled(false); props.getCircuit().setEnabled(false);
        List<Integer> broadcast = new java.util.ArrayList<>();
        gateway = new LlmGateway(primary, fallback, props, meters,
                new cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry(io.micrometer.tracing.Tracer.NOOP),
                event -> broadcast.add(((CapacityLimitChanged) event).limit()));
        when(primary.call(any(Prompt.class))).thenThrow(http(429));
        assertThrows(BizException.class, () -> gateway.call(new Prompt("test")));
        assertEquals(List.of(16), broadcast);
        when(primary.call(any(Prompt.class))).thenReturn(response("ok"));
        for (int i = 0; i < 20; i++) gateway.call(new Prompt("test"));
        assertEquals(List.of(16, 17), broadcast);
    }
    @Test void delayParsesHttpDateAndNeverShortensRetryAfter() {
        long now = java.time.Instant.parse("2026-09-13T00:00:00Z").toEpochMilli();
        HttpHeaders headers = new HttpHeaders(); headers.set("Retry-After", "Sun, 13 Sep 2026 00:00:10 GMT");
        var error = WebClientResponseException.create(429, "limited", headers, null, null);
        assertEquals(10000, LlmFailurePolicy.delayMs(error, 1, props.getRetry(), now));
        headers.set("Retry-After", "-1");
        assertTrue(LlmFailurePolicy.delayMs(WebClientResponseException.create(429, "limited", headers, null, null), 1, props.getRetry(), now) >= 0);
    }
    @Test void longRetryAfterFailsOverWithoutEarlyRetry() {
        HttpHeaders headers = new HttpHeaders(); headers.set("Retry-After", "60");
        when(primary.call(any(Prompt.class))).thenThrow(WebClientResponseException.create(429, "limited", headers, null, null));
        when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> create().call(new Prompt("test")));
        verify(primary).call(any(Prompt.class));
    }
}
