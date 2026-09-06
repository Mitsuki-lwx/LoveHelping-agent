package cn.lwx.lwxaiagent.config;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 降级备模型（RestClient 直连 dashscope 原生端点，2026-09-06）。
 * <p>背景链：OpenAI 兼容 /v1 网关对框架 WebClient 404（curl 200）→ 原生 DashScopeChatModel
 * 网络通但 400（body 契约差）——均与 curl 实测 200 的同构请求存在未知差异。本实现完全自控
 * body，与已验证 200 的 curl 请求（model/input.messages/parameters.result_format=message）
 * 逐一同构，保证降级可用。仅实现 call；stream 降级为同步结果包装。
 */
public class RestFallbackChatModel implements ChatModel {

    /** dashscope 原生生成端点（与 embedding 同域，网络层验证可达） */
    private static final String DASHSCOPE_GENERATION_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    private final RestClient restClient;
    private final String model;

    public RestFallbackChatModel(String apiKey, String model) {
        this.restClient = RestClient.builder()
                .baseUrl(DASHSCOPE_GENERATION_URL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.model = model;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatResponse call(Prompt prompt) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Message m : prompt.getInstructions()) {
            if (m.getText() == null || m.getText().isBlank()) {
                continue; // dashscope 原生端点拒空 content（curl 实测）
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("role", roleOf(m.getMessageType()));
            one.put("content", m.getText());
            msgs.add(one);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("messages", msgs);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("result_format", "message");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("parameters", parameters);

        Map<String, Object> resp = restClient.post().body(body).retrieve().body(Map.class);
        String text = extractText(resp);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("fallback dashscope returned empty content: "
                    + (resp == null ? "null" : resp.toString()));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> resp) {
        if (resp == null) return null;
        Object output = resp.get("output");
        if (output instanceof Map<?, ?> om && om.get("choices") instanceof List<?> choices
                && !choices.isEmpty() && choices.get(0) instanceof Map<?, ?> c
                && c.get("message") instanceof Map<?, ?> msg) {
            Object content = msg.get("content");
            return content == null ? null : content.toString();
        }
        return null;
    }

    private String roleOf(MessageType t) {
        return switch (t) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            default -> "user";
        };
    }

    @Override
    public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
        // 降级场景：同步全量结果包成单条流（可用性优先，非真流式）
        return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.just(call(prompt)));
    }
}
