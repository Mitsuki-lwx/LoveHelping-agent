package cn.lwx.lwxaiagent.infrastructure.orchestration;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.entity.GuardrailEvent;
import cn.lwx.lwxaiagent.harness.governance.GuardrailEventRecorder;
import cn.lwx.lwxaiagent.harness.governance.GuardrailRuleService;
import cn.lwx.lwxaiagent.harness.governance.JevSelfHarmSignal;
import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import cn.lwx.lwxaiagent.infrastructure.orchestration.graph.*;
import cn.lwx.lwxaiagent.infrastructure.scheduler.OnlineLoadTracker;
import cn.lwx.lwxaiagent.mapper.GuardrailEventMapper;
import cn.lwx.lwxaiagent.memory.MemoryService;
import cn.lwx.lwxaiagent.service.RateLimiter;
import cn.lwx.lwxaiagent.tenant.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
    GuardrailRuleService guards;
    GuardrailEventMapper eventMapper;
    @BeforeEach void setup() {
        graph = mock(GraphRunner.class); rate = mock(RateLimiter.class); memory = mock(MemoryService.class);
        streams = new StreamRegistry("discard"); meters = new SimpleMeterRegistry(); online = new OnlineLoadTracker(2, meters);
        guards = mock(GuardrailRuleService.class, RETURNS_DEEP_STUBS);
        eventMapper = mock(GuardrailEventMapper.class);
        entry = newEntry(signal(JevProperties.Mode.OFF, null));
        TenantContext.set("default", "user-a", "USER");
    }
    @AfterEach void cleanup() { TenantContext.clear(); meters.close(); }

    /** 第二信号默认 OFF = 接入前行为，故本类既有断言不受影响。 */
    private static JevSelfHarmSignal signal(JevProperties.Mode mode, String baseUrl) {
        var props = new JevProperties();
        props.setEnabled(baseUrl != null);
        props.setApiKey("apikey_test_only");
        if (baseUrl != null) props.setBaseUrl(baseUrl);
        props.setTimeoutMs(2000);
        props.getGuardrail().setMode(mode);
        props.getGuardrail().setMinProbability(0.6);
        return new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
    }

    private ChatEntry newEntry(JevSelfHarmSignal jevSignal) {
        return new ChatEntry(guards, jevSignal, new GuardrailEventRecorder(eventMapper), rate,
                new CapabilityRouter(), graph, streams, online, meters, Tracer.NOOP, memory, false, 23, 6, 80);
    }

    /** 本地 Jev 桩：固定返回某个自伤概率。 */
    private record JevStub(HttpServer server, AtomicInteger calls) implements AutoCloseable {
        static JevStub start(double probability) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/systemone", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] payload = ("{\"answers\":{\"self_harm\":{\"type\":\"noul\",\"noul\":" + probability + "}}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return new JevStub(server, calls);
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); }
    }
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
    @Test void slowSseSubscriberMustNotCancelHealthyGraphAfterFirstChunk() {
        CompletableFuture<Map<String,Object>> run = new CompletableFuture<>();
        when(graph.runAsync(anyMap(), anyString())).thenReturn(run);
        var received = new java.util.ArrayList<String>();
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var subscriber = new reactor.core.publisher.BaseSubscriber<String>() {
            @Override protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) { request(1); }
            @Override protected void hookOnNext(String value) { received.add(value); }
            @Override protected void hookOnError(Throwable failure) { error.set(failure); }
        };
        flux("slow", null).subscribe(subscriber);
        var sink = streams.get("slow");
        sink.append("first");
        sink.append("second");
        sink.append("third");
        assertNull(error.get(), "A normal MVC subscriber requests one chunk at a time; a burst must be buffered, not fail");
        subscriber.request(2);
        assertEquals(List.of("first", "second", "third"), received);
        subscriber.dispose();
    }

    @Test void genuinelyStalledSubscriberHasBoundedBufferAndCancelsWork() {
        CompletableFuture<Map<String,Object>> run = new CompletableFuture<>();
        when(graph.runAsync(anyMap(), anyString())).thenReturn(run);
        var subscriber = new reactor.core.publisher.BaseSubscriber<String>() {
            @Override protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) { request(1); }
            @Override protected void hookOnNext(String value) { }
            @Override protected void hookOnError(Throwable failure) { }
        };
        flux("stalled", null).subscribe(subscriber);
        var sink = streams.get("stalled");
        for (int i = 0; i < 300; i++) sink.append("x");
        verify(graph, atLeastOnce()).stop("stalled", run);
        subscriber.dispose();
        assertEquals(0, online.inFlight());
        assertEquals(0, streams.activeCount());
    }

    @Test void ownershipFailureNeverCallsModel() {
        doThrow(new BizException(403, "denied")).when(memory).claimConversation(anyString(), anyString(), anyString());
        assertThrows(BizException.class, () -> flux("other", null).blockLast());
        verifyNoInteractions(graph, rate); assertEquals(0, online.inFlight());
    }

    // ==================== 第二信号三态语义（docs/phase7-jev-shadow） ====================

    private static final String SELF_HARM_TEXT = "我真的撑不下去了，感觉活着好累";

    private GuardrailEvent onlyRecordedEvent() {
        ArgumentCaptor<GuardrailEvent> captor = ArgumentCaptor.forClass(GuardrailEvent.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * 影子观测的<b>定义性质</b>：越阈消息也绝不改变响应。
     * 一旦这里抛异常，那就不是影子而是灰度拦截，拿到的分布也被自己污染了。
     */
    @Test void shadowModeRecordsButNeverBlocks() throws Exception {
        try (JevStub stub = JevStub.start(0.99)) {
            ChatEntry shadow = newEntry(signal(JevProperties.Mode.SHADOW, stub.baseUrl()));
            assertDoesNotThrow(() -> shadow.chat(SELF_HARM_TEXT, "shadow-1", List.of(), false, false, null),
                    "shadow 模式下越阈消息不得抛异常");
            GuardrailEvent event = onlyRecordedEvent();
            assertEquals("SHADOW", event.getAction());
            assertEquals("jev:self_harm", event.getRuleId());
            assertEquals(0.99, event.getSignalScore().doubleValue(), 1e-9);
            assertEquals(0.6, event.getSignalThreshold().doubleValue(), 1e-9);
            assertEquals(1, stub.calls().get());
        }
    }

    /** 落库必须只有哈希：影子观测引入了概率字段，但不得引入原文。 */
    @Test void shadowEventStoresHashNotPlaintext() throws Exception {
        try (JevStub stub = JevStub.start(0.99)) {
            ChatEntry shadow = newEntry(signal(JevProperties.Mode.SHADOW, stub.baseUrl()));
            shadow.chat(SELF_HARM_TEXT, "shadow-2", List.of(), false, false, null);
            GuardrailEvent event = onlyRecordedEvent();
            assertNotEquals(SELF_HARM_TEXT, event.getContentHmac(), "不得存原文");
            assertNotNull(event.getContentHmac());
            assertEquals(64, event.getContentHmac().length(), "SHA-256 十六进制");
        }
    }

    /** 低于阈值的概率<b>也要落库</b>——否则观测只能看到高尾，算不出拦截占比。 */
    @Test void shadowModeRecordsBelowThresholdProbabilityToo() throws Exception {
        try (JevStub stub = JevStub.start(0.03)) {
            ChatEntry shadow = newEntry(signal(JevProperties.Mode.SHADOW, stub.baseUrl()));
            assertDoesNotThrow(() -> shadow.chat("今天上班，没什么特别的", "shadow-3", List.of(), false, false, null));
            assertEquals(0.03, onlyRecordedEvent().getSignalScore().doubleValue(), 1e-9);
        }
    }

    @Test void enforceModeBlocksAndRecordsBlocked() throws Exception {
        try (JevStub stub = JevStub.start(0.99)) {
            ChatEntry enforce = newEntry(signal(JevProperties.Mode.ENFORCE, stub.baseUrl()));
            BizException ex = assertThrows(BizException.class,
                    () -> enforce.chat(SELF_HARM_TEXT, "enforce-1", List.of(), false, false, null));
            assertEquals(4001, ex.getCode());
            assertTrue(ex.getMessage().contains("400-161-9995"), "必须是含援助热线的转介文案");
            assertEquals("BLOCKED", onlyRecordedEvent().getAction());
        }
    }

    /** enforce 模式下未越阈 = 无动作，不该留下审计记录（否则审计表会被噪声淹没）。 */
    @Test void enforceModeBelowThresholdRecordsNothing() throws Exception {
        try (JevStub stub = JevStub.start(0.10)) {
            ChatEntry enforce = newEntry(signal(JevProperties.Mode.ENFORCE, stub.baseUrl()));
            assertDoesNotThrow(() -> enforce.chat("今天上班，没什么特别的", "enforce-2", List.of(), false, false, null));
            verify(eventMapper, never()).insert(any(GuardrailEvent.class));
        }
    }

    /** 词典命中即短路：不应为"词典已能处理"的消息多付一次 Jev 往返。 */
    @Test void dictionaryHitShortCircuitsBeforeJev() throws Exception {
        doReturn(new GuardrailRuleService.Verdict(3, "self_harm")).when(guards).check(anyString());
        try (JevStub stub = JevStub.start(0.99)) {
            ChatEntry enforce = newEntry(signal(JevProperties.Mode.ENFORCE, stub.baseUrl()));
            assertThrows(BizException.class,
                    () -> enforce.chat(SELF_HARM_TEXT, "dict-1", List.of(), false, false, null));
            assertEquals(0, stub.calls().get(), "词典已判 L3 时不得调用 Jev");
        }
    }

    /**
     * 审计补全（2026-09-20）：词典 L3 拦截必须落库。
     *
     * <p>此前这里只记 meters，SQL 看不出"词典兜底拦了多少条"——与 ADR-6"用于误报率监控"的初衷不符。</p>
     */
    @Test void dictionaryL3BlockIsAudited() {
        doReturn(new GuardrailRuleService.Verdict(3, "self_harm")).when(guards).check(anyString());
        assertThrows(BizException.class,
                () -> entry.chat(SELF_HARM_TEXT, "dict-2", List.of(), false, false, null));
        GuardrailEvent event = onlyRecordedEvent();
        assertEquals("BLOCKED", event.getAction());
        assertEquals("self_harm", event.getRuleId(), "ruleId 必须原样取自词典判定，便于按规则统计");
        assertEquals(3, event.getLevel());
        assertNull(event.getSignalScore(), "词典路径没有第二信号概率，不得写 0 冒充");
        assertNotEquals(SELF_HARM_TEXT, event.getContentHmac(), "不得存原文");
    }

    /** 只补 L3：L1/L2 不阻断、请求会继续走到 LLM，而那条路径已由 GuardrailAdvisor 记 LOGGED。 */
    @Test void dictionaryL1L2IsNotRecordedHereToAvoidDoubleCounting() {
        doReturn(new GuardrailRuleService.Verdict(1, "vague")).when(guards).check(anyString());
        assertDoesNotThrow(() -> entry.chat("你好", "dict-3", List.of(), false, false, null));
        verify(eventMapper, never()).insert(any(GuardrailEvent.class));
    }
}
