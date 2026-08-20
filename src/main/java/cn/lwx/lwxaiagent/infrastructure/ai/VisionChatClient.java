package cn.lwx.lwxaiagent.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 视觉聊天客户端（ADR-11，手写 OpenAI 兼容调用）。
 * <p>
 * 背景：Spring AI 1.1.7 的 OpenAI 模型对 {@code Media} 的编码（Resource→file:// URI）
 * 无法被外部 OpenAI 兼容端点识别（E2E 实测 Resource/dataURL 两种方式图片均丢失）。
 * 此处手写 {@code /chat/completions} 请求：图片以 {@code image_url → data URL（base64）}
 * 发送，与 curl 直连验证通过的格式完全一致（mimo-v2.5 经 GoPlan 端点可正常看图）。
 * </p>
 * <p>企业级取舍：同步调用（非流式）优先保证正确性；流式改造与 spring-ai-alibaba
 * 替代（未来逐步替换原版 Spring AI）留待后续。</p>
 */
@Slf4j
@Component
public class VisionChatClient implements VisionPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String model;

    public VisionChatClient(@Value("${spring.ai.openai.base-url:}") String baseUrl,
                            @Value("${spring.ai.openai.api-key:}") String apiKey,
                            @Value("${app.llm.vision-model:}") String model) {
        this.model = model;
        // 与 curl 直连一致：{base}/v1/chat/completions；同步、长超时（视觉请求可达 30s+）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl + "/v1")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
                .build();
    }

    /**
     * 视觉对话：prompt + 多张图片（原始字节）→ 模型回复文本。
     *
     * @param prompt 用户文本
     * @param images 图片字节列表（≤4 张，调用方已校验）
     * @param mime   图片 MIME（image/png 等，所有图同类型）
     * @return 模型回复全文
     */
    public String chat(String prompt, List<byte[]> images, String mime) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        // 文本部分
        ObjectNode textPart = content.addObject();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        // 图片部分：data URL（base64）——curl 直连验证的可靠格式
        for (byte[] img : images) {
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            ObjectNode imageUrl = imgPart.putObject("image_url");
            imageUrl.put("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(img));
        }

        try {
            String respBody = restClient.post()
                    .uri("/chat/completions")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(respBody);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            log.warn("Vision chat failed: {}", e.getMessage());
            throw new cn.lwx.lwxaiagent.common.BizException(5000, "图片理解服务暂时不可用");
        }
    }
}
