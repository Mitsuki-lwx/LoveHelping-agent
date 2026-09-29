package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JEV 治理（ADR-63 / phase30）：并发闸门 + 熔断。
 *
 * <p>背景：JEV 是**唯一绕过 {@code LlmGateway}** 的外部依赖，此前无闸门、无熔断、无计量。
 * 而 `judge()` 在**用户发消息的主路径上**（`ChatEntry`，同步、先于 SSE 建流）——
 * 于是 JEV 故障时**每条消息都白等到 `timeoutMs`（8000ms）**。</p>
 *
 * <p>⛔ J1 的判据必须**用调用计数证明"没发请求"**：只看"返回了 empty"是不够的 ——
 * 那也可能只是又超时了一次，而那正是要修的东西。</p>
 */
class JevClientGovernanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JevProperties props(String baseUrl) {
        JevProperties p = new JevProperties();
        p.setEnabled(true);
        p.setApiKey("apikey_test_only");
        p.setBaseUrl(baseUrl);
        p.setTimeoutMs(400);
        return p;
    }

    private static JevClient client(JevProperties p) {
        return new JevClient(p, MAPPER, new AiTelemetry(Tracer.NOOP));
    }

    /** 起一个总是返回 500 的 stub，并数请求次数。 */
    private static HttpServer always500(AtomicInteger calls) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/systemone", ex -> {
            calls.incrementAndGet();
            byte[] b = "{\"error\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    @DisplayName("J1 熔断 fail-fast：连续失败达阈值后，后续调用**不再发 HTTP**（用计数证明）")
    void circuitOpensAndStopsSendingRequests() throws Exception {
        var calls = new AtomicInteger();
        HttpServer stub = always500(calls);
        try {
            JevProperties p = props("http://127.0.0.1:" + stub.getAddress().getPort());
            p.setFailureThreshold(2);
            p.setCircuitOpenMs(60_000);
            JevClient c = client(p);

            assertTrue(c.score("q `t`", "t", "x").isEmpty(), "第 1 次失败：回退");
            assertTrue(c.score("q `t`", "t", "x").isEmpty(), "第 2 次失败：回退并打开熔断");
            int afterOpen = calls.get();
            assertEquals(2, afterOpen, "前两次都应真的发过请求");

            // 熔断已开：后面这些**一次 HTTP 都不该发**
            for (int i = 0; i < 4; i++) {
                assertTrue(c.score("q `t`", "t", "x").isEmpty(), "熔断打开后仍应回退");
            }
            assertEquals(afterOpen, calls.get(),
                    "熔断打开后不得再发请求 —— 这正是把 8000ms 白等变成即时回退的地方");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("熔断窗口过后允许再试（半开），成功则恢复")
    void circuitAllowsRetryAfterWindow() throws Exception {
        var calls = new AtomicInteger();
        HttpServer stub = always500(calls);
        try {
            JevProperties p = props("http://127.0.0.1:" + stub.getAddress().getPort());
            p.setFailureThreshold(1);
            p.setCircuitOpenMs(1_000);   // @Min(1000)
            JevClient c = client(p);

            assertTrue(c.score("q `t`", "t", "x").isEmpty());
            int afterFirst = calls.get();
            assertTrue(c.score("q `t`", "t", "x").isEmpty(), "窗口内：走熔断，不发请求");
            assertEquals(afterFirst, calls.get());

            Thread.sleep(1_200);
            assertTrue(c.score("q `t`", "t", "x").isEmpty(), "窗口过后：允许再试一次");
            assertEquals(afterFirst + 1, calls.get(), "窗口过后应真的再发一次（半开）");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("J3 语义不变：未启用时**零请求**（熔断不得让它先发一次）")
    void disabledStillNeverCallsHttp() throws Exception {
        var calls = new AtomicInteger();
        HttpServer stub = always500(calls);
        try {
            JevProperties p = props("http://127.0.0.1:" + stub.getAddress().getPort());
            p.setEnabled(false);
            assertTrue(client(p).score("q `t`", "t", "x").isEmpty());
            assertEquals(0, calls.get(), "未启用时不得发出任何请求（闸门/熔断都不能越过这道前置判断）");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("J2 并发闸门生效：maxConcurrent 决定同时最多几个在途（这里只钉配置被读取）")
    void maxConcurrentIsConfigurable() {
        JevProperties p = props("http://127.0.0.1:1");
        p.setMaxConcurrent(3);
        assertEquals(3, p.getMaxConcurrent());
        // 信号量本身是 JDK 原语；这里钉"配置被读到、且非法值被兜住"
        p.setMaxConcurrent(0);
        assertEquals(0, p.getMaxConcurrent(), "属性层不做兜底（@Min(1) 负责校验）");
    }
}
