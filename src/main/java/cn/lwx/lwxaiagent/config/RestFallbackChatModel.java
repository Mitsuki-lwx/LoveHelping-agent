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

/** Native DashScope backup: finite HTTP deadlines, full tool protocol, cancellable async fallback. */
public class RestFallbackChatModel implements ChatModel {
    private static final URI ENDPOINT = URI.create("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
    private final HttpClient client;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public RestFallbackChatModel(String apiKey, String model) { this(apiKey, model, 3000, 25000); }
    public RestFallbackChatModel(String apiKey, String model, long connectMs, long readMs) {
        this.apiKey = apiKey;
        this.model = model;
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
            return HttpRequest.newBuilder(ENDPOINT).timeout(timeout)
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
