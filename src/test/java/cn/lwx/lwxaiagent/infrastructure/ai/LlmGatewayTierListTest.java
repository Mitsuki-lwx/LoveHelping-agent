package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADR-52 降级链**泛化**的专项测试 —— 验收 phase20 的 G1 / G2 两条目标。
 *
 * <p><b>为什么必须单独写</b>：{@code LlmGatewayThreeTierTest} 覆盖的是"ADT-48 那三级"
 * 的具体行为，而本类要证明的是**链的形状本身不再是硬编码的**：
 * <ul>
 *   <li><b>G1</b>：可以只注册某一级（ADR-51 §已知限制 口子 1 说"做不到"的场景）</li>
 *   <li><b>G2</b>：级数任意（口子 2：原来加第 3 级要改网关构造器签名）</li>
 * </ul>
 * 这正是 ADR-46 的教训形态：<b>旧用例全绿 ≠ 新能力被覆盖</b>。</p>
 *
 * <p>判据（先写死，见 {@code docs/phase20-tier-switch/checklist.md} D4a–D4e）：
 * 链的**顺序**与**成员**都必须可被断言，而不是"跑通了就算"。</p>
 */
class LlmGatewayTierListTest {

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
        // 单次尝试：本类验的是"链怎么走"，不是重试——重试会让 InOrder 断言复杂化
        props.getRetry().setMaxAttempts(1);
        props.setAttemptTimeoutMs(500);
        props.setFirstByteTimeoutMs(500);
        props.setTotalTimeoutMs(5000);
        props.setStreamIdleTimeoutMs(200);
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void cleanup() {
        if (gateway != null) gateway.close();
        meters.close();
        Thread.interrupted();
    }

    /** 用**任意级数**的链构造网关 —— 这是包私有构造器的用途（生产路径走 ObjectProvider）。 */
    private LlmGateway withTiers(LlmFallbackTier... tiers) {
        gateway = new LlmGateway(primary, List.of(tiers), props, meters,
                new cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry(
                        io.micrometer.tracing.Tracer.NOOP), event -> { }, null);
        return gateway;
    }

    private static LlmFallbackTier tier(String name) {
        return new LlmFallbackTier(name, mock(ChatModel.class));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder().model("mock").usage(new DefaultUsage(10, 5)).build()).build();
    }

    private static WebClientResponseException http(int code) {
        return WebClientResponseException.create(code, "test", HttpHeaders.EMPTY, null, null);
    }

    // ------------------------------------------------------------ 链的成员

    @Test
    @DisplayName("D4a 空链：主挂直接 E5000，不触碰任何降级目标")
    void emptyChainFailsFast() {
        when(primary.call(any(Prompt.class))).thenThrow(http(503));

        BizException e = assertThrows(BizException.class, () -> withTiers().call(new Prompt("t")));

        assertEquals(5000, e.getCode(), "空链应退化为「主链 + 重试」并如实报 E5000");
        assertNull(meters.find("llm.fallback").tag("provider", "fallback").counter(),
                "空链时不应出现任何降级计数");
    }

    @Test
    @DisplayName("D4b ⭐ G1 只注册 last-resort（不开 DashScope）→ 主挂后直接走它")
    void singleTierIsIndependentlyUsable() {
        var only = tier("last-resort");
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(only.model().call(any(Prompt.class))).thenReturn(response("only-tier"));

        assertEquals("only-tier", withTiers(only).call(new Prompt("t")).getResult().getOutput().getText());
        // 指标 tag 必须如实反映"这一级的名字"，而不是被写成 "fallback"
        assertEquals(1.0, meters.get("llm.fallback").tag("provider", "last-resort")
                .tag("outcome", "started").counter().count());
    }

    @Test
    @DisplayName("D4c ⭐ G2 三个 tier → 严格按注册顺序依次走完")
    void threeTiersWalkInOrder() {
        var t1 = tier("t1");
        var t2 = tier("t2");
        var t3 = tier("t3");
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(t1.model().call(any(Prompt.class))).thenThrow(http(503));
        when(t2.model().call(any(Prompt.class))).thenThrow(http(503));
        when(t3.model().call(any(Prompt.class))).thenReturn(response("t3-answered"));

        assertEquals("t3-answered", withTiers(t1, t2, t3).call(new Prompt("t")).getResult().getOutput().getText());

        InOrder inOrder = inOrder(primary, t1.model(), t2.model(), t3.model());
        inOrder.verify(primary).call(any(Prompt.class));
        inOrder.verify(t1.model()).call(any(Prompt.class));
        inOrder.verify(t2.model()).call(any(Prompt.class));
        inOrder.verify(t3.model()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("D4d 总闸 degrade-enabled=false → 有 3 个 tier 也一级都不走")
    void masterSwitchBeatsRegisteredTiers() {
        var t1 = tier("t1");
        var t2 = tier("t2");
        props.setDegradeEnabled(false);
        when(primary.call(any(Prompt.class))).thenThrow(http(503));

        assertThrows(BizException.class, () -> withTiers(t1, t2).call(new Prompt("t")));
        verifyNoInteractions(t1.model(), t2.model());
    }

    @Test
    @DisplayName("中间级挂掉不影响后续级被尝试（不是「一级挂全链停」）")
    void middleTierFailureDoesNotAbortChain() {
        var t1 = tier("t1");
        var t2 = tier("t2");
        when(primary.call(any(Prompt.class))).thenThrow(http(503));
        when(t1.model().call(any(Prompt.class))).thenThrow(http(500));
        when(t2.model().call(any(Prompt.class))).thenReturn(response("t2-answered"));

        assertEquals("t2-answered", withTiers(t1, t2).call(new Prompt("t")).getResult().getOutput().getText());
    }

    // ------------------------------------------------------------ 流式侧

    @Test
    @DisplayName("D4e 流式同样按链走（degradingStream 泛化后未被改坏）")
    void streamWalksTheSameChain() {
        var t1 = tier("t1");
        var t2 = tier("t2");
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(t1.model().stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));
        when(t2.model().stream(any(Prompt.class))).thenReturn(Flux.just(response("stream-t2")));

        var last = withTiers(t1, t2).stream(new Prompt("t")).blockLast(Duration.ofSeconds(5));

        assertNotNull(last, "链末级应接住");
        assertEquals("stream-t2", last.getResult().getOutput().getText());
    }

    @Test
    @DisplayName("流式空链：主挂即报错，不静默给空")
    void streamEmptyChainErrors() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(http(503)));

        BizException e = assertThrows(BizException.class,
                () -> withTiers().stream(new Prompt("t")).blockLast(Duration.ofSeconds(5)));
        assertEquals(5000, e.getCode());
    }

    // ------------------------------------------------------------ 装配契约

    @Test
    @DisplayName("级别重名 → 启动期直接失败，而不是静默共用一个熔断器")
    void duplicateTierNameFailsFast() {
        var a = new LlmFallbackTier("dup", mock(ChatModel.class));
        var b = new LlmFallbackTier("dup", mock(ChatModel.class));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> withTiers(a, b));
        assertTrue(e.getMessage().contains("dup"), "报错要说清是哪个名字重了：" + e.getMessage());
    }

    @Test
    @DisplayName("tier 名字为空 → 构造期即拒绝（它是指标 tag，空值会污染观测）")
    void blankTierNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LlmFallbackTier(" ", mock(ChatModel.class)));
        assertThrows(NullPointerException.class, () -> new LlmFallbackTier("x", null));
    }

    @Test
    @DisplayName("生效端点指标带上 tier 自报的 baseUrl（ADR-52 修口子 3）")
    void endpointMetricUsesTierReportedBaseUrl() {
        var t = new LlmFallbackTier("t1", mock(ChatModel.class), "https://example.test/v1");
        withTiers(t);

        var counter = meters.get("llm.endpoint.configured").tag("level", "t1").counter();
        assertNotNull(counter, "tier 级应报出生效端点");
        assertTrue(counter.getId().getTag("target").contains("https://example.test/v1"),
                "target 应含 tier 自报的 baseUrl，实际=" + counter.getId().getTag("target"));
    }

    @Test
    @DisplayName("tier 未报 baseUrl 时只写模型/类名，绝不回退成主端点 URL（假端点更有害）")
    void endpointMetricNeverFallsBackToPrimaryUrl() {
        var t = tier("t1"); // baseUrl = ""
        withTiers(t);

        var counter = meters.get("llm.endpoint.configured").tag("level", "t1").counter();
        assertNotNull(counter);
        assertFalse(counter.getId().getTag("target").contains("deepseek"),
                "不该把主端点 URL 张冠李戴到降级级上，实际=" + counter.getId().getTag("target"));
    }

    @Test
    @DisplayName("空链时只有 primary 一条生效端点指标（ADR-51 起的当前形态）")
    void emptyChainReportsOnlyPrimary() {
        withTiers();

        var levels = new ArrayList<String>();
        meters.getMeters().stream()
                .filter(m -> m.getId().getName().equals("llm.endpoint.configured"))
                .forEach(m -> levels.add(m.getId().getTag("level")));
        assertEquals(List.of("primary"), levels, "空链只应报 primary，实际=" + levels);
    }
}
