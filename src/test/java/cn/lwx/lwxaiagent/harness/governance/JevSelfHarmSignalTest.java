package cn.lwx.lwxaiagent.harness.governance;

import cn.lwx.lwxaiagent.infrastructure.ai.JevClient;
import cn.lwx.lwxaiagent.infrastructure.ai.JevProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第二信号的单测：**只加召回、不替兜底**，且任何失败都必须安静地返回空。
 * 用本地 HttpServer 桩（非 mock 框架），模式同 {@code LangfuseTracingTest}。
 *
 * <p>本类还守着"判定与决策分离"（{@code docs/phase7-jev-shadow}）：
 * {@code judge()} 必须把<b>低于阈值</b>的概率也返回出来，否则影子观测只能看到分布的高尾，
 * 而那恰恰是它要测量的主体。</p>
 */
class JevSelfHarmSignalTest {

    private record Stub(HttpServer server, AtomicInteger calls) implements AutoCloseable {
        static Stub start(int status, String body) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/systemone", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return new Stub(server, calls);
        }
        public void close() { server.stop(0); }
    }

    private static String noulBody(double probability) {
        return "{\"model\":\"jev-1.13.0\",\"answers\":{\"self_harm\":{\"type\":\"noul\",\"noul\":" + probability
                + "}},\"usage\":{\"input_tokens\":300,\"output_tokens\":6}}";
    }

    private JevSelfHarmSignal signal(Stub stub, JevProperties.Mode mode, double threshold) {
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
        props.setTimeoutMs(2000);
        props.getGuardrail().setMode(mode);
        props.getGuardrail().setMinProbability(threshold);
        return new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
    }

    @Test
    void exceedsThresholdWhenProbabilityReachesIt() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.97))) {
            var risk = signal(stub, JevProperties.Mode.ENFORCE, 0.9).judge("我真的撑不下去了，感觉活着好累");
            assertTrue(risk.isPresent());
            assertEquals(0.97, risk.get().probability(), 1e-9);
            assertTrue(risk.get().exceedsThreshold());
            assertEquals(1, stub.calls().get());
        }
    }

    /** 阈值是"达到即算"（>=），不是严格大于——边界必须明确，否则等价于偷偷抬高门槛。 */
    @Test
    void thresholdBoundaryIsInclusive() throws Exception {
        try (Stub exactly = Stub.start(200, noulBody(0.9));
             Stub below = Stub.start(200, noulBody(0.89))) {
            assertTrue(signal(exactly, JevProperties.Mode.ENFORCE, 0.9).judge("x").orElseThrow().exceedsThreshold());
            assertFalse(signal(below, JevProperties.Mode.ENFORCE, 0.9).judge("x").orElseThrow().exceedsThreshold());
        }
    }

    /**
     * 影子观测的关键前提：<b>低于阈值也要把概率返回出来</b>。
     *
     * <p>若这里返回空，观测就只剩高尾，算不出"阈值 0.6 会拦掉真实流量的百分之几"。</p>
     */
    @Test
    void shadowModeStillReturnsProbabilityBelowThreshold() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.05))) {
            var risk = signal(stub, JevProperties.Mode.SHADOW, 0.6).judge("今天上班，没什么特别的");
            assertTrue(risk.isPresent(), "低于阈值也必须返回概率，否则影子观测看不到分布主体");
            assertEquals(0.05, risk.get().probability(), 1e-9);
            assertFalse(risk.get().exceedsThreshold());
        }
    }

    /** 落库要记阈值，日后阈值改了才能复算"这条当时为什么没拦"。 */
    @Test
    void thresholdIsExposedForAudit() {
        JevProperties props = new JevProperties();
        props.getGuardrail().setMinProbability(0.42);
        var signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
        assertEquals(0.42, signal.threshold(), 1e-9);
    }

    @Test
    void offModeNeverCallsHttp() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.99))) {
            var signal = signal(stub, JevProperties.Mode.OFF, 0.9);
            assertFalse(signal.enabled());
            assertTrue(signal.judge("我不想活了").isEmpty());
            assertEquals(0, stub.calls().get(), "off 模式下不得发出任何请求");
        }
    }

    /** 失败即"不加召回"，且不得抛异常——护栏路径上抛异常比漏召回更危险。 */
    @Test
    void failuresReturnEmptyAndNeverThrow() throws Exception {
        try (Stub rateLimited = Stub.start(429, "{\"error\":\"rate limited\"}");
             Stub overloaded = Stub.start(529, "{}");
             Stub malformed = Stub.start(200, "{\"answers\":{\"self_harm\":{\"type\":\"noul\"}}}")) {
            for (Stub stub : new Stub[]{rateLimited, overloaded, malformed}) {
                assertTrue(signal(stub, JevProperties.Mode.ENFORCE, 0.9).judge("我不想活了").isEmpty(),
                        "失败必须安静地返回空");
            }
        }
    }

    @Test
    void unreachableEndpointReturnsEmpty() {
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:1");
        props.setTimeoutMs(500);
        props.getGuardrail().setMode(JevProperties.Mode.ENFORCE);
        JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
        assertDoesNotThrow(() -> assertTrue(signal.judge("我不想活了").isEmpty()));
    }

    @Test
    void blankInputIsNotSent() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.99))) {
            var signal = signal(stub, JevProperties.Mode.SHADOW, 0.6);
            assertTrue(signal.judge(null).isEmpty());
            assertTrue(signal.judge("   ").isEmpty());
            assertEquals(0, stub.calls().get(), "空文本不发请求");
        }
    }

    /** 总开关关掉时，即使护栏模式是 enforce 也不得发出请求。 */
    @Test
    void masterSwitchOverridesGuardrailMode() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.99))) {
            JevProperties props = new JevProperties();
            props.setEnabled(false);
            props.setApiKey("apikey_test_only");
            props.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
            props.getGuardrail().setMode(JevProperties.Mode.ENFORCE);
            JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
            assertFalse(signal.enabled());
            assertTrue(signal.judge("我不想活了").isEmpty());
            assertEquals(0, stub.calls().get());
        }
    }
}
