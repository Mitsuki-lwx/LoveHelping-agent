package cn.lwx.lwxaiagent.rag.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LocalDocumentReranker} 单测（ADR-25 本地模式 + Phase 8 ADR-36 remote 模式）。
 *
 * <h3>为什么用内嵌 HttpServer 而不是 mock</h3>
 * <p>该类用 JDK {@code HttpClient} 直接发报文，mock 掉客户端会把"请求体长什么样"这一
 * 最关键的契约（{@code model} 字段、{@code Authorization} 头）一并 mock 掉，
 * 测试就失去意义。故起一个真实本地 HTTP 服务端，**断言它收到的报文**。</p>
 */
class LocalDocumentRerankerTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    /** 服务端本次响应体（由各用例设置）。 */
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> responseStatus = new AtomicReference<>(200);

    private RerankProperties props;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", this::handle);
        server.start();
        port = server.getAddress().getPort();

        props = new RerankProperties();
        props.setEnabled(true);
        props.setMode("local");
        props.setUrl("http://127.0.0.1:" + port + "/rerank");
        props.setTopN(20);
        props.setTopK(5);
        props.setTimeoutMs(3000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] out = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus.get(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private LocalDocumentReranker reranker() {
        return new LocalDocumentReranker(props, new ObjectMapper(), "");
    }

    private LocalDocumentReranker remoteReranker(String apiKey) {
        return new LocalDocumentReranker(props, new ObjectMapper(), apiKey);
    }

    private List<Document> docs(int n) {
        List<Document> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Document("文档" + (i + 1) + " 内容"));
        }
        return list;
    }

    /** 构造合规响应：results 条数必须等于 top_n。 */
    private String resultsJson(int[][] indexScorePairs) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < indexScorePairs.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(indexScorePairs[i][0])
              .append(",\"relevance_score\":").append(indexScorePairs[i][1]).append('}');
        }
        return sb.append("]}").toString();
    }

    // ------------------------------------------------------------------
    // 基本行为
    // ------------------------------------------------------------------

    @Test
    void rerank_reordersByRelevanceScore() {
        // 候选 5 个，topK=3 → 服务端返回 3 条；文档2(索引1) 分最高
        responseBody.set(resultsJson(new int[][]{{0, 10}, {1, 99}, {2, 50}}));
        List<Document> out = reranker().rerank("查询", docs(5), 3);

        assertEquals(3, out.size());
        assertTrue(out.get(0).getText().startsWith("文档2"), "分最高者排首位");
        assertTrue(out.get(1).getText().startsWith("文档3"));
        assertTrue(out.get(2).getText().startsWith("文档1"));
    }

    @Test
    void rerank_candidatesNotMoreThanTopK_skipsHttpCall() {
        // count<=k 时直接返回，不应发请求（否则纯浪费一次网络往返）
        props.setTopN(10);
        List<Document> out = reranker().rerank("查询", docs(3), 5);
        assertEquals(3, out.size());
        assertNull(lastBody.get(), "候选数不足 topK 时不应发起 HTTP 请求");
    }

    @Test
    void rerank_emptyOrNullCandidates_returnsEmpty() {
        assertTrue(reranker().rerank("查询", List.of(), 5).isEmpty());
        assertTrue(reranker().rerank("查询", null, 5).isEmpty());
    }

    @Test
    void rerank_zeroOrNegativeTopK_returnsEmpty() {
        assertTrue(reranker().rerank("查询", docs(5), 0).isEmpty());
        assertTrue(reranker().rerank("查询", docs(5), -1).isEmpty());
    }

    // ------------------------------------------------------------------
    // 解析边界（checklist: index 越界 / 重复 → 拒绝）
    // ------------------------------------------------------------------

    @Test
    void parse_indexOutOfRange_throws() {
        // 索引 99 越界：宁可失败暴露，也不返回错排结果
        responseBody.set(resultsJson(new int[][]{{0, 1}, {99, 2}, {2, 3}}));
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    @Test
    void parse_duplicateIndex_throws() {
        // 索引 0 出现两次：契约违约，必须拒绝
        responseBody.set(resultsJson(new int[][]{{0, 1}, {0, 2}, {2, 3}}));
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    @Test
    void parse_resultCountMismatch_throws() {
        // 只返回 2 条但 top_n=3：条数不符
        responseBody.set(resultsJson(new int[][]{{0, 1}, {1, 2}}));
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    @Test
    void parse_nonFiniteScore_throws() {
        responseBody.set("{\"results\":[{\"index\":0,\"relevance_score\":1e999},"
                + "{\"index\":1,\"relevance_score\":1},{\"index\":2,\"relevance_score\":2}]}");
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    // ------------------------------------------------------------------
    // HTTP 失败（checklist: 非 200 / 超时 → 抛错，由 postprocessor 降级）
    // ------------------------------------------------------------------

    @Test
    void rerank_httpNon200_throws() {
        responseStatus.set(500);
        responseBody.set("{\"error\":\"boom\"}");
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    @Test
    void rerank_oversizedResponse_throws() {
        // >16384 字符的响应体一律拒绝（防异常上游把内存打满）
        responseStatus.set(200);
        responseBody.set("{\"results\":[{\"index\":0,\"relevance_score\":1,\"pad\":\""
                + "x".repeat(20000) + "\"}]}");
        assertThrows(IllegalStateException.class, () -> reranker().rerank("查询", docs(5), 3));
    }

    @Test
    void rerank_afterFailures_circuitOpens() {
        // 连续失败达到阈值后熔断打开，后续调用快速失败（不再打上游）
        props.setFailureThreshold(2);
        responseStatus.set(500);
        LocalDocumentReranker r = reranker();
        assertThrows(IllegalStateException.class, () -> r.rerank("q", docs(5), 3));
        assertThrows(IllegalStateException.class, () -> r.rerank("q", docs(5), 3));
        // 第三次：熔断已开 → 抛出的消息不同（"circuit open"）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> r.rerank("q", docs(5), 3));
        assertTrue(e.getMessage().contains("circuit open"), "熔断打开后应快速失败: " + e.getMessage());
    }

    // ------------------------------------------------------------------
    // local 模式请求体：不应带 model / Authorization
    // ------------------------------------------------------------------

    @Test
    void localMode_requestBody_hasNoModelOrAuth() {
        responseBody.set(resultsJson(new int[][]{{0, 1}, {1, 2}, {2, 3}}));
        reranker().rerank("查询", docs(5), 3);

        String body = lastBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"query\""), "请求体应含 query");
        assertTrue(body.contains("\"documents\""), "请求体应含 documents");
        assertTrue(body.contains("\"top_n\""), "请求体应含 top_n");
        assertFalse(body.contains("\"model\""), "local 模式不应带 model 字段");
        assertNull(lastAuth.get(), "local 模式不应带 Authorization 头");
    }

    // ------------------------------------------------------------------
    // remote 模式（Phase 8 / ADR-36）：必须带 model + Authorization
    // ------------------------------------------------------------------

    @Test
    void remoteMode_requestBody_hasModelAndAuth() {
        props.setMode("remote");
        props.setModel("Qwen/Qwen3-Reranker-8B");
        responseBody.set(resultsJson(new int[][]{{0, 1}, {1, 2}, {2, 3}}));

        remoteReranker("sk-test-key").rerank("查询", docs(5), 3);

        String body = lastBody.get();
        assertNotNull(body);
        assertTrue(body.contains("\"model\":\"Qwen/Qwen3-Reranker-8B\""),
                "remote 模式必须带 model 字段: " + body);
        assertEquals("Bearer sk-test-key", lastAuth.get(), "remote 模式必须带 Bearer 鉴权头");
    }

    @Test
    void remoteMode_withoutApiKey_throwsAtConstruction() {
        // 构造期即失败：避免运行到线上才发现没配 key
        props.setMode("remote");
        assertThrows(IllegalStateException.class, () -> remoteReranker(""));
        assertThrows(IllegalStateException.class, () -> remoteReranker(null));
    }

    @Test
    void remoteMode_nonLoopbackHttp_rejected() {
        // remote 指向公网时必须 https：http 会让密钥明文过网。
        // 注意用**非环回**地址——环回地址是允许 http 的（本地联调例外）。
        props.setMode("remote");
        props.setUrl("http://api.siliconflow.cn/v1/rerank");
        assertThrows(IllegalArgumentException.class, () -> remoteReranker("sk-test-key"));
    }

    @Test
    void remoteMode_loopbackHttp_allowed() {
        // 环回地址允许 http（单测装置与本地联调依赖此例外）
        props.setMode("remote");
        props.setUrl("http://127.0.0.1:" + port + "/rerank");
        assertDoesNotThrow(() -> remoteReranker("sk-test-key"));
    }

    @Test
    void constructor_urlWithUserInfo_rejected() {
        // URL 内嵌凭据会被日志记录，禁止
        props.setUrl("http://user:pass@127.0.0.1:" + port + "/rerank");
        assertThrows(IllegalArgumentException.class, () -> reranker());
    }

    @Test
    void constructor_invalidScheme_rejected() {
        props.setUrl("ftp://127.0.0.1:" + port + "/rerank");
        assertThrows(IllegalArgumentException.class, () -> reranker());
    }

    // ------------------------------------------------------------------
    // 截断
    // ------------------------------------------------------------------

    @Test
    void rerank_truncatesCandidateText() {
        props.setMaxCandidateChars(4); // "文档1 内容" → 只保留前 4 字符
        responseBody.set(resultsJson(new int[][]{{0, 1}, {1, 2}, {2, 3}}));
        List<Document> candidates = List.of(
                new Document("文档一号很长很长很长很长"),
                new Document("文档二号很长很长很长很长"),
                new Document("文档三号很长很长很长很长"),
                new Document("文档四号很长很长很长很长"));
        reranker().rerank("查询", candidates, 3);
        assertTrue(lastBody.get().contains("文档一号"), "截断后仍可识别");
        assertFalse(lastBody.get().contains("很长很长很长很长"), "超出上限的部分应被截断");
    }

    @Test
    void rerank_topNLimitsSentCandidates() {
        // topN=3 只送前 3 个候选；topK=2 < count=3 才会真发请求
        props.setTopN(3);
        responseBody.set(resultsJson(new int[][]{{0, 1}, {1, 2}}));
        reranker().rerank("查询", docs(10), 2);
        String body = lastBody.get();
        assertNotNull(body, "count>k 时应发起请求");
        assertTrue(body.contains("文档3"), "前 3 个候选应被送出: " + body);
        assertFalse(body.contains("文档4"), "超过 topN 的候选不应被送出: " + body);
    }

    /** 确认解析出的结构可被 ObjectMapper 反序列化（防止测试假绿）。 */
    @Test
    void parse_sanity_objectMapperRoundTrip() throws Exception {
        var node = new ObjectMapper().readTree(resultsJson(new int[][]{{0, 5}}));
        assertEquals(1, node.path("results").size());
        assertEquals(5, node.path("results").get(0).path("relevance_score").asInt());
    }
}
