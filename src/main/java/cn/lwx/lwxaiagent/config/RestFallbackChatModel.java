package cn.lwx.lwxaiagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Native DashScope backup: finite HTTP deadlines, full tool protocol, cancellable async fallback.
 *
 * <p>⛔ <b>ADR-51（2026-09-27）：本类默认不再被注册为 bean。</b>
 * {@link #DEFAULT_ENDPOINT} 所在域名 {@code dashscope.aliyuncs.com} 在本机<b>不可达</b>：
 * DNS 解析到 Clash fake-ip（{@code 198.18.0.138} / {@code fdfe:dcba:9876::c5}），
 * 直连与走代理的 TLS 握手均被中断。Java 侧同形失败最早见于 2026-09-16
 * （{@code ResourceAccessException: ... Remote host terminated the handshake}）。
 * 复活方式：把 {@code app.llm.fallback-enabled} 置 {@code true}（见
 * {@code ChatModelConfig#dashScopeFallbackTier}）。</p>
 *
 * <p><b>ADR-52</b>：端点从类常量改为<b>构造器参数</b>（{@code app.llm.fallback.base-url}），
 * 换供应商不必改本类 —— 原 {@code ENDPOINT} 降级为默认值 {@link #DEFAULT_ENDPOINT}。</p>
 *
 * <p>⚠️ <b>本类刻意零日志</b>——这曾导致一次真实的误判（ADR-51 记）：
 * 它是降级链上唯一"失败不留痕"的一级，于是「日志里 grep 不到 dashscope」
 * 被读成了「没调用 dashscope」。排障时请改用指标
 * （{@code llm_call_total{provider="fallback"}}），不要 grep 日志。</p>
 */
public class RestFallbackChatModel implements ChatModel {

    /**
     * DashScope 原生文本生成端点（默认值）。
     * <p>ADR-52 起可被 {@code app.llm.fallback.base-url} 覆盖；
     * 本常量同时供配置类取默认值（{@code @Value} 占位符的 default 段）。</p>
     */
    public static final String DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final HttpClient client;
    private final String apiKey;
    private final String model;
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public RestFallbackChatModel(String apiKey, String model) { this(apiKey, model, 3000, 25000); }

    public RestFallbackChatModel(String apiKey, String model, long connectMs, long readMs) {
        this(apiKey, model, DEFAULT_ENDPOINT, connectMs, readMs);
    }

    /**
     * @param endpoint 完整请求 URL；{@code null}/空 时回退到 {@link #DEFAULT_ENDPOINT}
     */
    public RestFallbackChatModel(String apiKey, String model, String endpoint, long connectMs, long readMs) {
        this.apiKey = apiKey;
        this.model = model;
        this.endpoint = URI.create(endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint);
        this.timeout = Duration.ofMillis(readMs);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).build();
    }

    @Override public ChatResponse call(Prompt prompt) {
        try { return parse(client.send(request(prompt), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Fallback cancelled"); }
        catch (java.io.IOException e) { throw new org.springframework.web.client.ResourceAccessException("Fallback transport failed", e); }
    }

    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        // One full result on the backup, but no blocking call on Reactor/event-loop threads.
        return Mono.fromFuture(() -> client.sendAsync(request(prompt), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
                .map(this::parse).flux();
    }

    private HttpRequest request(Prompt prompt) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Fallback credentials are not configured");
        try {
            return HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(prompt)))) .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid model request", e); }
    }

    Map<String, Object> payload(Prompt prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage tool) {
                for (var response : tool.getResponses()) messages.add(Map.of(
                        "role", "tool", "tool_call_id", response.id(), "name", response.name(), "content", response.responseData()));
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("role", message.getMessageType().getValue());
            one.put("content", message.getText() == null ? "" : message.getText());
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                one.put("tool_calls", assistant.getToolCalls().stream().map(t -> Map.of(
                        "id", t.id(), "type", "function", "function", Map.of("name", t.name(), "arguments", t.arguments()))).toList());
            }
            messages.add(one);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("result_format", "message");
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                && !options.getToolCallbacks().isEmpty()) {
            parameters.put("tools", options.getToolCallbacks().stream().map(cb -> {
                var d = cb.getToolDefinition();
                try { return Map.of("type", "function", "function", Map.of(
                        "name", d.name(), "description", d.description(), "parameters", json.readTree(d.inputSchema()))); }
                catch (Exception e) { throw new IllegalArgumentException("Invalid tool schema", e); }
            }).toList());
        }
        return Map.of("model", model, "input", Map.of("messages", messages), "parameters", parameters);
    }

    private ChatResponse parse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach(headers::put);
            throw new RestClientResponseException("Fallback HTTP " + response.statusCode(), response.statusCode(),
                    "", headers, new byte[0], StandardCharsets.UTF_8);
        }
        try {
            JsonNode root = json.readTree(response.body());
            JsonNode message = root.path("output").path("choices").path(0).path("message");
            String text = message.path("content").asText("");
            List<AssistantMessage.ToolCall> tools = new ArrayList<>();
            for (JsonNode t : message.path("tool_calls")) tools.add(new AssistantMessage.ToolCall(
                    t.path("id").asText(), "function", t.path("function").path("name").asText(),
                    t.path("function").path("arguments").asText("{}")));
            var assistant = AssistantMessage.builder().content(text).toolCalls(tools).build();
            JsonNode usage = root.path("usage");
            return ChatResponse.builder().generations(List.of(new Generation(assistant)))
                    .metadata(ChatResponseMetadata.builder().model(model)
                            .usage(new DefaultUsage(usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt())).build()).build();
        } catch (Exception e) { throw new IllegalStateException("Invalid fallback response", e); }
    }
}
