package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADR-48 三级降级链的专项测试。
 *
 * <p><b>为什么必须单独写</b>：旧的 27 个 {@code LlmGatewayTest} 用例全部走
 * <b>两参构造器</b>（lastResort=null），它们只能证明"两级链没被改坏"，
 * <b>证明不了第三级会被走到</b>。这正是 ADR-46 的教训形态：测试全绿 ≠ 新行为被覆盖。</p>
 *
 * <p>逐条对应 docs/phase15-openrouter-primary/checklist.md 的 C8 / C9。</p>
 */
class LlmGatewayThreeTierTest {

    private ChatModel primary;
    private ChatModel fallback;
    private ChatModel lastResort;
    private LlmGatewayProperties props;
    private SimpleMeterRegistry meters;
    private LlmGateway gateway;

    @BeforeEach
    void setup() {
        primary = mock(ChatModel.class);
        fallback = mock(ChatModel.class);
        lastResort = mock(ChatModel.class);
        props = new LlmGatewayProperties();
        props.getRetry().setBackoffMs(1);
        props.getRetry().setJitter(0);
        props.setAttemptTimeoutMs(500);
        props.setTotalTimeoutMs(5000);
        props.setStreamIdleTimeoutMs(100);
        meters = new SimpleMeterRegistry();
    }

    /** 三级链专用工厂 —— 旧测试用的是两参构造器，覆盖不到 lastResort。 */
    private LlmGateway createThreeTier() {
        gateway = new LlmGateway(primary, fallback, lastResort, props, meters,
                new cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry(
                        io.micrometer.tracing.Tracer.NOOP), event -> { }, null);
        return gateway;
    }

    /** 两级链（回归：lastResort 为 null 时行为必须与旧版逐字一致）。 */
    private LlmGateway createTwoTier() {
        gateway = new LlmGateway(primary, fallback, props, meters);
        return gateway;
    }

    @AfterEach
    void cleanup() {
        if (gateway != null) gateway.close();
        meters.close();
        Thread.interrupted();
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().model("mock").usage(new DefaultUsage(10, 5)).build()).build();
    }

    private static WebClientResponseException http(int code) {
        return WebClientResponseException.create(code, "test", HttpHeaders.EMPTY, null, null);
    }

    // ---------------------------------------------------------------- call 侧

    @Test
    @DisplayName("C8-1 主挂 → 用备一（fallback），不该碰第三级")
    void primaryFailsUsesFallback() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));

        assertEquals("backup", createThreeTier().call(new Prompt("t")).getResult().getOutput().getText());
        verifyNoInteractions(lastResort);
    }

    @Test
    @DisplayName("C8-2 主挂 + 备一挂 → 用第三级（bigmodel 兜底）")
    void primaryAndFallbackFailUsesLastResort() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(fallback.call(any(Prompt.class))).thenThrow(http(503));
        when(lastResort.call(any(Prompt.class))).thenReturn(response("last-resort"));

        assertEquals("last-resort", createThreeTier().call(new Prompt("t")).getResult().getOutput().getText());
        verify(primary, times(3)).call(any(Prompt.class));   // 主级重试到上限
        verify(fallback, times(1)).call(any(Prompt.class));   // 降级不重试
        verify(lastResort, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("C8-3 三级全挂 → 抛 BizException(5000)，不是裸异常")
    void allThreeFailThrowsBizException() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(fallback.call(any(Prompt.class))).thenThrow(http(503));
        when(lastResort.call(any(Prompt.class))).thenThrow(http(503));

        BizException e = assertThrows(BizException.class, () -> createThreeTier().call(new Prompt("t")));
        assertEquals(5000, e.getCode());
    }

    @Test
    @DisplayName("C7 lastResort 未注入时退回两级，且绝不触碰 null 目标")
    void nullLastResortDegradesToTwoTiers() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));

        assertEquals("backup", createTwoTier().call(new Prompt("t")).getResult().getOutput().getText());
    }

    @Test
    @DisplayName("C7 fallback 缺失但 lastResort 存在 → 仍能降级（不能只看 fallback 非空）")
    void fallbackNullButLastResortPresentStillDegrades() {
        gateway = new LlmGateway(primary, null, lastResort, props, meters,
                new cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry(
                        io.micrometer.tracing.Tracer.NOOP), event -> { }, null);
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(lastResort.call(any(Prompt.class))).thenReturn(response("last-resort"));

        // 这条正是 canFallback 曾只看 fallback != null 会漏掉的分支
        assertEquals("last-resort", gateway.call(new Prompt("t")).getResult().getOutput().getText());
    }

    @Test
    @DisplayName("C6 客户端 400 不重试也不降级 —— 请求本身有问题，换谁都一样")
    void clientErrorNeitherRetriesNorDegrades() {
        when(primary.call(any(Prompt.class))).thenThrow(http(400));

        assertThrows(BizException.class, () -> createThreeTier().call(new Prompt("t")));
        verify(primary, times(1)).call(any(Prompt.class));
        verifyNoInteractions(fallback, lastResort);
    }

    /**
     * ADR-48 实测缺口：OpenRouter 余额不足返回 402，而 402 原先不在降级白名单里
     * → 主级 402 时三级链一次都不走，直接抛 5000，用户看到"服务暂时不可用"。
     *
     * <p>但 402 恰恰是<b>最该降级</b>的故障：换一家供应商就能继续服务。
     * 这个用例是本 bug 的回归锁。</p>
     */
    @Test
    @DisplayName("ADR-48 402 额度耗尽 → 必须降级（但不在主级重试）")
    void paymentRequiredDegradesButDoesNotRetryPrimary() {
        when(primary.call(any(Prompt.class))).thenThrow(http(402));
        when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));

        assertEquals("backup", createThreeTier().call(new Prompt("t")).getResult().getOutput().getText());
        // 关键：402 不该让主级按 max-attempts 空转（重试同一个余额不足的端点毫无意义）
        verify(primary, times(1)).call(any(Prompt.class));
        verify(fallback, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("ADR-48 402 且备一也 402 → 落到第三级而不是硬失败")
    void paymentRequiredFallsThroughToLastResort() {
        when(primary.call(any(Prompt.class))).thenThrow(http(402));
        when(fallback.call(any(Prompt.class))).thenThrow(http(402));
        when(lastResort.call(any(Prompt.class))).thenReturn(response("last-resort"));

        assertEquals("last-resort", createThreeTier().call(new Prompt("t")).getResult().getOutput().getText());
    }

    @Test
    @DisplayName("ADR-48 402 流式同样要能降级（E2E 走的就是流式）")
    void streamPaymentRequiredDegrades() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(402)));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("backup")));

        var last = createThreeTier().stream(new Prompt("t")).blockLast(Duration.ofSeconds(5));
        assertNotNull(last, "402 后应降级而不是报错");
        assertEquals("backup", last.getResult().getOutput().getText());
    }

    @Test
    @DisplayName("fallback-enabled=false 时三级一个都不走（回滚开关）")
    void fallbackDisabledSkipsWholeChain() {
        props.setFallbackEnabled(false);
        when(primary.call(any(Prompt.class))).thenThrow(http(503));

        assertThrows(BizException.class, () -> createThreeTier().call(new Prompt("t")));
        verifyNoInteractions(fallback, lastResort);
    }

    @Test
    @DisplayName("各级降级有独立指标标签，能从 Prometheus 分辨是谁在服务")
    void degradeChainEmitsDistinctProviderLabels() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(lastResort.call(any(Prompt.class))).thenReturn(response("last-resort"));

        createThreeTier().call(new Prompt("t"));

        assertEquals(1.0, meters.get("llm.fallback").tag("provider", "fallback").tag("outcome", "started").counter().count());
        assertEquals(1.0, meters.get("llm.fallback").tag("provider", "last-resort").tag("outcome", "started").counter().count());
    }

    // ------------------------------------------------------------- 流式侧

    @Test
    @DisplayName("C6-流式 主挂 → 备一挂 → 第三级接住")
    void streamDegradesThroughThreeTiers() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(lastResort.stream(any(Prompt.class))).thenReturn(Flux.just(response("last-resort")));

        var last = createThreeTier().stream(new Prompt("t")).blockLast(Duration.ofSeconds(5));
        assertNotNull(last, "第三级应接住并给出回答");
        assertEquals("last-resort", last.getResult().getOutput().getText());
    }

    @Test
    @DisplayName("C6-流式 已 emit 内容后不再降级重放（不把用户已看到的话重复一遍）")
    void streamDoesNotReplayAfterEmit() {
        when(primary.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("partial")).concatWith(Flux.error(http(503))));

        var seen = new java.util.ArrayList<String>();
        assertThrows(BizException.class, () -> createThreeTier().stream(new Prompt("t"))
                .doOnNext(r -> seen.add(r.getResult().getOutput().getText()))
                .blockLast(Duration.ofSeconds(5)));
        assertEquals(List.of("partial"), seen);
        // 关键断言：吐过内容后，一次都不许降级
        verifyNoInteractions(fallback, lastResort);
    }

    @Test
    @DisplayName("C6-流式 三级全挂 → 报错，不静默给空")
    void streamAllTiersFailErrors() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(lastResort.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));

        BizException e = assertThrows(BizException.class,
                () -> createThreeTier().stream(new Prompt("t")).blockLast(Duration.ofSeconds(5)));
        assertEquals(5000, e.getCode());
    }

    // --------------------------------------------------------- 熔断独立性

    @Test
    @DisplayName("C9 主熔断打开时不空转重试，直接走下一级")
    void openPrimaryCircuitMovesStraightToFallback() {
        var c = props.getCircuit();
        c.setSlidingWindowSize(5);
        c.setMinimumNumberOfCalls(1);
        c.setFailureRateThreshold(0.5);
        c.setOpenMs(60_000);
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(fallback.call(any(Prompt.class))).thenReturn(response("backup"));

        var g = createThreeTier();
        // 先把主级熔断打满
        for (int i = 0; i < 3; i++) {
            try { g.call(new Prompt("warm-" + i)); } catch (RuntimeException ignored) { }
        }
        var before = mockingDetails(primary).getInvocations().size();
        assertEquals("backup", g.call(new Prompt("after")).getResult().getOutput().getText());
        // 熔断打开后主级调用次数不应再增长到 max-attempts
        assertTrue(mockingDetails(primary).getInvocations().size() - before <= 1,
                "主熔断已开，不应继续按 max-attempts 空转");
    }

    @Test
    @DisplayName("C3 兜底级故障不污染主级熔断（各自独立计数）")
    void lastResortFailuresDoNotOpenPrimaryCircuit() {
        var c = props.getCircuit();
        c.setSlidingWindowSize(5);
        c.setMinimumNumberOfCalls(1);
        c.setFailureRateThreshold(0.5);
        c.setOpenMs(60_000);
        // 主级一直成功，兜底级一直 503：主级不该被拖开
        when(primary.call(any(Prompt.class))).thenReturn(response("primary"));
        when(fallback.call(any(Prompt.class))).thenThrow(http(503));
        when(lastResort.call(any(Prompt.class))).thenThrow(http(503));

        var g = createThreeTier();
        for (int i = 0; i < 5; i++) {
            assertEquals("primary", g.call(new Prompt("t" + i)).getResult().getOutput().getText());
        }
    }
}
