package cn.lwx.lwxaiagent.rag.rerank;

import cn.lwx.lwxaiagent.infrastructure.ai.ProviderCircuit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Local CPU cross-encoder. Failure is handled by the postprocessor; never fall back to a paid LLM. */
@lombok.extern.slf4j.Slf4j
@Component
public class LocalDocumentReranker implements DocumentReranker {
    private final RerankProperties props;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Semaphore permits;
    private final ProviderCircuit circuit;
    private final URI endpoint;
    /** remote（硅基流动）模式下的 API Key；来自环境变量 SF_API_KEY，不落盘。 */
    private final String apiKey;

    public LocalDocumentReranker(RerankProperties props, ObjectMapper json,
                                 @org.springframework.beans.factory.annotation.Value("${app.siliconflow.api-key:}") String siliconFlowApiKey) {
        this.props = props; this.json = json;
        this.apiKey = siliconFlowApiKey;
        endpoint = URI.create(props.getUrl());
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Invalid local reranker URL");
        // remote 模式校验顺序：先查密钥、再查协议。
        // 反过来的话，"没配 SF_API_KEY"会先撞上"URL 必须 https"的报错，
        // 把排查方向带偏到 URL 上——这是实际踩过的误导（单测 remoteMode_withoutApiKey 抓出）。
        if (props.isRemote() && (this.apiKey == null || this.apiKey.isBlank()))
            throw new IllegalStateException("RERANK_MODE=remote 但 SF_API_KEY 未配置");
        // remote 模式指向公网厂商端点，必须 https——否则密钥会以明文过网。
        // 例外：环回地址（127.0.0.1/localhost/::1）允许 http，用于本地联调与单测装置；
        // 环回流量不出网卡，无明文泄露风险。
        if (props.isRemote() && !"https".equals(endpoint.getScheme()) && !isLoopback(endpoint.getHost()))
            throw new IllegalArgumentException("Remote reranker URL must use https: " + props.getUrl());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        permits = new Semaphore(props.getMaxConcurrent());
        circuit = new ProviderCircuit(true, props.getFailureThreshold(), props.getCircuitOpenMs());
        // 生效配置回显（2026-09-21）。此前本类**一行日志都没有**，于是"重排究竟走了本地 8091
        // 还是远端 8B"无法从日志回答——实测踩到：8091 恰好也在监听时，两种可能无法区分，
        // 只能靠"耗时/命中数变化"间接推断。这条 INFO 在启动时把 endpoint/mode/model 钉死。
        // 不含密钥（只回显非敏感项）。
        log.info("Rerank configured: enabled={} mode={} endpoint={} model={} topN={} topK={} timeoutMs={}",
                props.isEnabled(), props.getMode(), props.getUrl(),
                props.isRemote() ? props.getModel() : "(local 模式不带 model)",
                props.getTopN(), props.getTopK(), props.getTimeoutMs());
    }

    @Override public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) return List.of();
        int count = Math.min(candidates.size(), props.getTopN());
        int k = Math.min(topK, count);
        if (count <= k) return List.copyOf(candidates.subList(0, k));
        var ticket = circuit.acquire();
        if (ticket == null) throw new IllegalStateException("Local reranker circuit open");
        if (!permits.tryAcquire()) { ticket.cancel(); throw new IllegalStateException("Local reranker saturated"); }
        try {
            List<String> docs = candidates.subList(0, count).stream().map(d -> truncate(d.getText(), props.getMaxCandidateChars())).toList();
            // remote（硅基流动）与 local 的协议体一致，仅多一个 model 字段：
            //   请求 {query, documents, top_n[, model]} / 响应 results[].{index, relevance_score}
            // 因此不新建类、不开分叉逻辑，只在同一处按 mode 增补字段与鉴权头。
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", truncate(query, 2000));
            payload.put("documents", docs);
            payload.put("top_n", k);
            if (props.isRemote()) {
                payload.put("model", props.getModel());
            }
            String body = json.writeValueAsString(payload);
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json");
            // 鉴权头按 apiKey 是否配置条件添加：本地 reranker 服务不需要，
            // 强行带上 Bearer 空串反而会被某些网关判为鉴权失败。
            if (props.isRemote() && apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            var request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            // DEBUG 粒度：每次调用一行，供排查"这条路有没有被走到/多久"
            // （INFO 级别在 ADR-33 的降噪口径下只留启动配置回显，避免高 QPS 刷屏）。
            log.debug("Rerank call -> {} model={} candidates={} topK={}",
                    endpoint, props.isRemote() ? props.getModel() : "(local)", count, k);
            long t0 = System.nanoTime();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 16384) throw new IllegalStateException("Invalid local reranker response");
            List<Document> result = parse(response.body(), candidates.subList(0, count), k);
            log.debug("Rerank ok <- {} in {} ms ({} -> {} docs)",
                    endpoint, (System.nanoTime() - t0) / 1_000_000, count, result.size());
            ticket.success();
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); ticket.cancel();
            throw new java.util.concurrent.CancellationException("Rerank cancelled");
        } catch (Exception e) {
            ticket.failure();
            throw new IllegalStateException("Local reranker unavailable", e);
        } finally { ticket.cancel(); permits.release(); }
    }

    List<Document> parse(String body, List<Document> candidates, int k) throws Exception {
        JsonNode rows = json.readTree(body).path("results");
        if (!rows.isArray() || rows.size() != k) throw new IllegalArgumentException("Rerank result count mismatch");
        Set<Integer> seen = new HashSet<>();
        List<Scored> scores = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode index = row.path("index"), relevance = row.path("relevance_score");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || !relevance.isNumber()) throw new IllegalArgumentException("Rerank result type mismatch");
            int i = index.asInt(); double score = relevance.asDouble();
            if (i < 0 || i >= candidates.size() || !seen.add(i) || !Double.isFinite(score)) throw new IllegalArgumentException("Invalid rerank index/score");
            scores.add(new Scored(i, score));
        }
        scores.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::index));
        return scores.stream().map(s -> candidates.get(s.index())).toList();
    }
    private static String truncate(String text, int limit) { return text == null ? "" : text.substring(0, Math.min(text.length(), limit)); }

    /** 是否为环回地址：这些地址的流量不出网卡，允许 http（本地联调/单测装置）。 */
    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    private record Scored(int index, double score) {}
}
