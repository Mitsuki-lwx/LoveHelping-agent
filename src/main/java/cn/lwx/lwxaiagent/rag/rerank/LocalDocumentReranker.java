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
        // remote 模式缺 key 的语义（2026-09-21 改，ADR-39）：
        // **不再在构造期抛出**，改到 rerank() 的调用期抛。
        //
        // 为什么改：`mode=remote` 已成为**默认值**（application.yml）。若本类在构造期硬失败，
        // 任何没配 SF_API_KEY 的环境（CI、别人的机器、正处于回滚中的生产）**整个应用起不来**
        // —— 而重排只是**增强项**，不该有这种能力。这也与 SiliconFlowEmbeddingModel 一致：
        // 它在 doEmbed() 里才检查 key，所以 embedding 缺 key 同样不影响启动。
        // 调用期抛出后由 RerankDocumentPostProcessor 捕获 → 降级为原顺序 → 对话不受影响。
        //
        // 协议校验仍留在构造期：它不依赖密钥，且是安全属性（公网必须 https，否则密钥明文过网）；
        // 环回地址允许 http，供本地联调与单测装置使用。
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
        // maxConcurrent / failureThreshold 一并回显（2026-09-23 补）：它们是**决定降级率**的参数——
        // 实测因为没回显，一波压测里 58 次重排中 45 次降级"究竟是不是并发许可不足"，
        // 只能靠翻源码 + 交叉 3 个计数推断（Rerank call 在 tryAcquire 之后，
        // 拿不到许可时连那一行都不会打）。
        log.info("Rerank configured: enabled={} mode={} endpoint={} model={} topN={} topK={} timeoutMs={} maxConcurrent={} failureThreshold={}",
                props.isEnabled(), props.getMode(), props.getUrl(),
                props.isRemote() ? props.getModel() : "(local 模式不带 model)",
                props.getTopN(), props.getTopK(), props.getTimeoutMs(),
                props.getMaxConcurrent(), props.getFailureThreshold());
    }

    @Override public List<Document> rerank(String query, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty() || topK <= 0) return List.of();
        int count = Math.min(candidates.size(), props.getTopN());
        int k = Math.min(topK, count);
        if (count <= k) return List.copyOf(candidates.subList(0, k));
        // remote 模式缺 key：**调用期**失败（不再构造期抛，见类构造器注释 / ADR-39）。
        // 放在真正发请求之前、且**在 circuit 之前**——这是**配置问题**不是上游故障，
        // 不该计入熔断器的失败窗口（否则缺 key 会把熔断器一并打开，掩盖真实上游状态）。
        // 报错直接点名 SF_API_KEY，避免排查方向被引到 URL / 网络上去。
        if (props.isRemote() && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException(
                    "RERANK_MODE=remote 需要 SF_API_KEY（当前未配置）。"
                            + "可设 RERANK_ENABLED=false 关闭重排，或 RERANK_MODE=local 走本地服务");
        }
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
