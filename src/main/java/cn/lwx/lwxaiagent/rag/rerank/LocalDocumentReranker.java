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
@Component
public class LocalDocumentReranker implements DocumentReranker {
    private final RerankProperties props;
    private final ObjectMapper json;
    private final HttpClient client;
    private final Semaphore permits;
    private final ProviderCircuit circuit;
    private final URI endpoint;

    public LocalDocumentReranker(RerankProperties props, ObjectMapper json) {
        this.props = props; this.json = json;
        endpoint = URI.create(props.getUrl());
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null) throw new IllegalArgumentException("Invalid local reranker URL");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        permits = new Semaphore(props.getMaxConcurrent());
        circuit = new ProviderCircuit(true, props.getFailureThreshold(), props.getCircuitOpenMs());
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
            String body = json.writeValueAsString(Map.of("query", truncate(query, 2000), "documents", docs, "top_n", k));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 16384) throw new IllegalStateException("Invalid local reranker response");
            List<Document> result = parse(response.body(), candidates.subList(0, count), k);
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
    private record Scored(int index, double score) {}
}
