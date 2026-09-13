package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.*;
import cn.lwx.lwxaiagent.infrastructure.scheduler.OnlineLoadTracker;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatEntryTest {
    GraphRunner graph;
    RateLimiter rate;
    MemoryService memory;
    StreamRegistry streams;
    OnlineLoadTracker online;
    ChatEntry entry;
    SimpleMeterRegistry meters;
    @BeforeEach void setup() {
        graph = mock(GraphRunner.class); rate = mock(RateLimiter.class); memory = mock(MemoryService.class);
        streams = new StreamRegistry("discard"); meters = new SimpleMeterRegistry(); online = new OnlineLoadTracker(2, meters);
        GuardrailRuleService guards = mock(GuardrailRuleService.class, RETURNS_DEEP_STUBS);
        entry = new ChatEntry(guards, rate, new CapabilityRouter(), graph, streams, online, meters, Tracer.NOOP, memory, false, 23, 6, 80);
        TenantContext.set("default", "user-a", "USER");
    }
    @AfterEach void cleanup() { TenantContext.clear(); meters.close(); }
    Flux<String> flux(String id, BiConsumer<Boolean,String> cb) {
        return ((AgentResult.ShallowResult) entry.chat("你好", id, List.of(), false, cb)).flux();
    }
    @Test void noSubscriptionDoesNotConsumeCapacityOrQuota() {
        flux("test", null); assertEquals(0, online.inFlight()); verifyNoInteractions(rate, graph, memory);
    }
    @Test void successCleansRegistryAndPermit() {
        when(graph.runAsync(anyMap(), anyString())).thenReturn(CompletableFuture.completedFuture(Map.of(GraphStateKeys.OUTPUT, "ok")));
        assertEquals(List.of("ok"), flux("test", null).collectList().block());
        assertEquals(0, online.inFlight()); assertEquals(0, streams.activeCount());
        verify(rate).acquire("user-a"); verify(memory).claimConversation("user-a", "test", "love");
    }
    @Test void failedAdmissionReleasesPermitAndCallsTaskCallbackOnce() {
        doThrow(new BizException(429, "busy")).when(rate).acquire(anyString());
        AtomicInteger failed = new AtomicInteger();
        assertThrows(BizException.class, () -> flux("test", (ok, e) -> { assertFalse(ok); failed.incrementAndGet(); }).blockLast());
        assertEquals(1, failed.get()); assertEquals(0, online.inFlight()); verifyNoInteractions(graph);
    }
    @Test void cancelStopsActualRunAndReleasesAllResources() {
        CompletableFuture<Map<String,Object>> run = new CompletableFuture<>(); when(graph.runAsync(anyMap(), anyString())).thenReturn(run);
        var subscription = flux("test", null).subscribe(); assertEquals(1, online.inFlight());
        subscription.dispose();
        verify(graph, atLeastOnce()).stop("test", run);
        assertEquals(0, online.inFlight()); assertEquals(0, streams.activeCount());
    }
    @Test void totalTimeoutStopsGraphAndDoesNotPretendSuccess() {
        CompletableFuture<Map<String,Object>> run = new CompletableFuture<>(); when(graph.runAsync(anyMap(), anyString())).thenReturn(run);
        assertThrows(RuntimeException.class, () -> flux("test", null).blockLast(Duration.ofSeconds(1)));
        verify(graph, timeout(500).atLeastOnce()).stop("test", run);
        assertEquals(0, online.inFlight()); assertEquals(0, streams.activeCount());
    }
    @Test void duplicateSubscriberCannotRunGraphTwice() {
        when(graph.runAsync(anyMap(), anyString())).thenReturn(CompletableFuture.completedFuture(Map.of(GraphStateKeys.OUTPUT, "ok")));
        var f = flux("test", null); f.blockLast();
        assertThrows(BizException.class, f::blockLast); verify(graph).runAsync(anyMap(), anyString());
    }
    @Test void concurrentSameSessionCannotReplaceFirstSink() {
        when(graph.runAsync(anyMap(), anyString())).thenReturn(new CompletableFuture<>());
        var first = flux("test", null).subscribe(); var sink = streams.get("test");
        assertThrows(BizException.class, () -> flux("test", null).blockLast());
        assertSame(sink, streams.get("test")); first.dispose();
        assertEquals(0, online.inFlight()); verify(graph).runAsync(anyMap(), anyString());
    }
    @Test void ownershipFailureNeverCallsModel() {
        doThrow(new BizException(403, "denied")).when(memory).claimConversation(anyString(), anyString(), anyString());
        assertThrows(BizException.class, () -> flux("other", null).blockLast());
        verifyNoInteractions(graph, rate); assertEquals(0, online.inFlight());
    }
}
