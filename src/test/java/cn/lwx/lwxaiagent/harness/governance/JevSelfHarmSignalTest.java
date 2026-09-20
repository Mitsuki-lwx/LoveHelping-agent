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
 * 第二信号的单测：**只加召回、不替兜底**，且任何失败都必须安静地返回 false。
 * 用本地 HttpServer 桩（非 mock 框架），模式同 {@code LangfuseTracingTest}。
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

    private JevSelfHarmSignal signal(Stub stub, boolean enabled, double threshold) {
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
        props.setTimeoutMs(2000);
        props.getGuardrail().setEnabled(enabled);
        props.getGuardrail().setMinProbability(threshold);
        return new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
    }

    @Test
    void flagsWhenProbabilityReachesThreshold() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.97))) {
            assertTrue(signal(stub, true, 0.9).flagged("我真的撑不下去了，感觉活着好累"));
            assertEquals(1, stub.calls().get());
        }
    }

    /** 阈值是"达到即算"（>=），不是严格大于——边界必须明确，否则等价于偷偷抬高门槛。 */
    @Test
    void thresholdBoundaryIsInclusive() throws Exception {
        try (Stub exactly = Stub.start(200, noulBody(0.9));
             Stub below = Stub.start(200, noulBody(0.89))) {
            assertTrue(signal(exactly, true, 0.9).flagged("x"));
            assertFalse(signal(below, true, 0.9).flagged("x"));
        }
    }

    @Test
    void disabledNeverCallsHttp() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.99))) {
            assertFalse(signal(stub, false, 0.9).flagged("我不想活了"));
            assertEquals(0, stub.calls().get(), "未启用时不得发出任何请求");
        }
    }

    /** 失败即"不加召回"，且不得抛异常——护栏路径上抛异常比漏召回更危险。 */
    @Test
    void failuresReturnFalseAndNeverThrow() throws Exception {
        try (Stub rateLimited = Stub.start(429, "{\"error\":\"rate limited\"}");
             Stub overloaded = Stub.start(529, "{}");
             Stub malformed = Stub.start(200, "{\"answers\":{\"self_harm\":{\"type\":\"noul\"}}}")) {
            for (Stub stub : new Stub[]{rateLimited, overloaded, malformed}) {
                assertFalse(signal(stub, true, 0.9).flagged("我不想活了"), "失败必须安静地返回 false");
            }
        }
    }

    @Test
    void unreachableEndpointReturnsFalse() {
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:1");
        props.setTimeoutMs(500);
        props.getGuardrail().setEnabled(true);
        JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
        assertDoesNotThrow(() -> assertFalse(signal.flagged("我不想活了")));
    }

    /** 总开关关掉时，即使护栏开关开着也不得发出请求。 */
    @Test
    void masterSwitchOverridesGuardrailSwitch() throws Exception {
        try (Stub stub = Stub.start(200, noulBody(0.99))) {
            JevProperties props = new JevProperties();
            props.setEnabled(false);
            props.setApiKey("apikey_test_only");
            props.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
            props.getGuardrail().setEnabled(true);
            JevSelfHarmSignal signal = new JevSelfHarmSignal(new JevClient(props, new ObjectMapper()), props);
            assertFalse(signal.enabled());
            assertFalse(signal.flagged("我不想活了"));
            assertEquals(0, stub.calls().get());
        }
    }
}
