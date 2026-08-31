package cn.lwx.lwxaiagent.infrastructure.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Langfuse 上报器（Langfuse 2.x 手写签名接入，参考 CodeForge 的 Python SDK 认证方式）。
 * <p>认证 = Authorization: Basic base64(publicKey:secretKey)（与官方 MCP 配置一致，本地 Langfuse 3.x 认此方式）。</p>
 * <p>异步 fire-and-forget：上报失败仅告警，不阻塞业务。</p>
 */
@Slf4j
@Component
public class LangfuseReporter {

    private final LangfuseProperties props;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    public LangfuseReporter(ObjectProvider<LangfuseProperties> propsProvider) {
        this.props = propsProvider.getIfAvailable();
    }

    /** 上报一次执行（trace + generation 合并一条 ingestion batch） */
    public void report(String traceId, String name, String input, String output, long durationMs, String model) {
        if (props == null || !props.isEnabled()) {
            return;
        }
        String ts = Instant.now().toString();
        Map<String, Object> traceCreate = new LinkedHashMap<>();
        traceCreate.put("id", traceId);
        traceCreate.put("type", "trace-create");
        traceCreate.put("timestamp", ts);
        Map<String, Object> traceBody = new LinkedHashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", name == null ? "chat" : name);
        traceBody.put("input", input == null ? "" : input);
        traceBody.put("output", output == null ? "" : output);
        traceCreate.put("body", traceBody);

        Map<String, Object> genCreate = new LinkedHashMap<>();
        genCreate.put("id", UUID.randomUUID().toString());
        genCreate.put("type", "generation-create");
        genCreate.put("timestamp", ts);
        Map<String, Object> genBody = new LinkedHashMap<>();
        genBody.put("traceId", traceId);
        genBody.put("name", name == null ? "chat" : name);
        genBody.put("model", model == null ? "unknown" : model);
        genBody.put("input", input == null ? "" : input);
        genBody.put("output", output == null ? "" : output);
        if (durationMs > 0) {
            genBody.put("startTime", Instant.now().minusMillis(durationMs).toString());
            genBody.put("endTime", ts);
        }
        genCreate.put("body", genBody);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch", List.of(traceCreate, genCreate));
        payload.put("metadata", Map.of());
        String bodyJson;
        try {
            bodyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Langfuse payload serialize failed: {}", e.getMessage());
            return;
        }

        try {
            String basic = "Basic " + Base64.getEncoder().encodeToString(
                    (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getHost() + "/api/public/ingestion"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", basic)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            // fire-and-forget
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() < 300) {
                            log.info("Langfuse report ok trace={} status={}", traceId, resp.statusCode());
                        } else {
                            log.warn("Langfuse report failed trace={} status={} body={}",
                                    traceId, resp.statusCode(), resp.body().length() > 200 ? resp.body().substring(0, 200) : resp.body());
                        }
                    })
                    .exceptionally(e -> {
                        log.warn("Langfuse report error trace={}: {}", traceId, e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("Langfuse report exception trace={}: {}", traceId, e.getMessage());
        }
    }
}