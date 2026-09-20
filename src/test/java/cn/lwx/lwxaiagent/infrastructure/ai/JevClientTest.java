package cn.lwx.lwxaiagent.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JevClient 单测：用**本地 HttpServer 桩**（非 mock 框架），模式同 {@code LangfuseTracingTest}。
 * 关注三件事：请求体形状是否符合官方 API、档位→分值映射、以及**任何失败都返回空而不抛**。
 */
class JevClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 起一个只回固定响应的桩，并把收到的请求体留档。 */
    private record Stub(HttpServer server, AtomicReference<String> lastBody, AtomicInteger calls) implements AutoCloseable {
        static Stub start(int status, String body) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<String> lastBody = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            server.createContext("/v1/systemone", exchange -> {
                calls.incrementAndGet();
                lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, payload.length);
                exchange.getResponseBody().write(payload);
                exchange.close();
            });
            server.start();
            return new Stub(server, lastBody, calls);
        }
        public void close() { server.stop(0); }
    }

    private JevClient clientFor(Stub stub, boolean enabled) {
        JevProperties props = new JevProperties();
        props.setEnabled(enabled);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
        props.setTimeoutMs(2000);
        return new JevClient(props, MAPPER);
    }

    private static String moodBody(double score) {
        return "{\"model\":\"jev-1.13.0\",\"answers\":{\"mood\":{\"type\":\"score\",\"score\":" + score
                + ",\"legend\":{\"0\":\"非常糟糕或处于危机：绝望、想不开、崩溃\",\"1\":\"低落、难过、沮丧\","
                + "\"2\":\"平静、中立、没有明显起伏\",\"3\":\"有起色、稍微好一些\",\"4\":\"明显变好、积极、有希望\"},"
                + "\"probabilities\":{\"0\":0.0,\"1\":0.05,\"2\":0.9,\"3\":0.05,\"4\":0.0},\"confidence\":0.83}},"
                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":18}}";
    }

    @Test
    void requestShapeMatchesOfficialApiAndScoreIsParsed() throws Exception {
        try (Stub stub = Stub.start(200, moodBody(1.05))) {
            var mood = clientFor(stub, true).score(
                    "根据 `user_text`，判断情绪状态。", "user_text", "  我今天很难过  ");
            assertTrue(mood.isPresent());
            assertEquals(1, mood.get().level());
            assertEquals("低落、难过、沮丧", mood.get().label());

            JsonNode sent = MAPPER.readTree(stub.lastBody().get());
            assertEquals("jev-latest", sent.path("model").asText());
            assertEquals("我今天很难过", sent.path("state").path("user_text").asText(), "state 字段名要与 instructions 里的引用一致，且已去空白");
            JsonNode question = sent.path("questions").path("mood");
            assertEquals("score", question.path("type").asText());
            assertEquals(5, question.path("criteria").size(), "档位数必须与本地常量一致");
            assertTrue(question.path("instructions").asText().contains("user_text"));
        }
    }

    @Test
    void levelMapsOntoExistingScoreDomain() throws Exception {
        try (Stub worst = Stub.start(200, moodBody(0.0));
             Stub best = Stub.start(200, moodBody(4.0));
             Stub middle = Stub.start(200, moodBody(2.0))) {
            assertEquals(-2, clientFor(worst, true).score("q `t`", "t", "x").orElseThrow().toScore());
            assertEquals(2, clientFor(best, true).score("q `t`", "t", "x").orElseThrow().toScore());
            assertEquals(0, clientFor(middle, true).score("q `t`", "t", "x").orElseThrow().toScore());
        }
    }

    /** 官方说 score 可以落在两档之间；我们按"就近取整"映射回离散档位（不是截断）。 */
    @Test
    void fractionalScoreRoundsToNearestLevel() throws Exception {
        try (Stub down = Stub.start(200, moodBody(3.4));
             Stub up = Stub.start(200, moodBody(3.6))) {
            assertEquals(3, clientFor(down, true).score("q `t`", "t", "x").orElseThrow().level());
            assertEquals(4, clientFor(up, true).score("q `t`", "t", "x").orElseThrow().level());
        }
    }

    @Test
    void failuresReturnEmptyInsteadOfThrowing() throws Exception {
        // 429 限流 / 529 过载 / 200 但缺 score 字段 / 200 但档位越界，都必须返回空而不是抛异常
        try (Stub rateLimited = Stub.start(429, "{\"error\":\"rate limited\"}");
             Stub overloaded = Stub.start(529, "{}");
             Stub broken = Stub.start(200, "{\"answers\":{\"mood\":{\"type\":\"score\"}}}");
             Stub outOfRange = Stub.start(200, moodBody(9.0))) {
            for (Stub stub : new Stub[]{rateLimited, overloaded, broken, outOfRange}) {
                assertTrue(clientFor(stub, true).score("q `t`", "t", "x").isEmpty(), "失败必须返回空以触发回退");
            }
        }
    }

    @Test
    void disabledOrKeylessClientNeverCallsHttp() throws Exception {
        try (Stub stub = Stub.start(200, moodBody(2.0))) {
            assertTrue(clientFor(stub, false).score("q `t`", "t", "x").isEmpty());
            assertEquals(0, stub.calls().get(), "未启用时不得发出任何请求");

            JevProperties noKey = new JevProperties();
            noKey.setEnabled(true);
            noKey.setApiKey("  ");
            noKey.setBaseUrl("http://127.0.0.1:" + stub.server().getAddress().getPort());
            assertTrue(new JevClient(noKey, MAPPER).score("q `t`", "t", "x").isEmpty());
            assertEquals(0, stub.calls().get(), "没有密钥时不得发出任何请求");
        }
    }

    /** 不可达端点必须快速失败并返回空（连接层异常也算失败，不能抛给调用方）。 */
    @Test
    void unreachableEndpointReturnsEmpty() {
        JevProperties props = new JevProperties();
        props.setEnabled(true);
        props.setApiKey("apikey_test_only");
        props.setBaseUrl("http://127.0.0.1:1");   // 必然连不上
        props.setTimeoutMs(500);
        assertTrue(new JevClient(props, MAPPER).score("q `t`", "t", "x").isEmpty());
    }
}
