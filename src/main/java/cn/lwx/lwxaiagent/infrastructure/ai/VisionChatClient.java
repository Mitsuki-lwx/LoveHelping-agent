package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.config.LlmProviderProperties;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
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
import io.micrometer.tracing.Span;

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
    /** 补全路径（provider 级可配，默认 {@code /v1/chat/completions}）。 */
    private final String completionsPath;
    /** 非流式响应信封字段名（如 cline.bot 的 {@code data}）；null = 无信封。 */
    private final String responseEnvelope;
    /** ADR-62/F3：只为让这条绕网关的链路在 Langfuse 里可见。 */
    private final AiTelemetry telemetry;

    /**
     * ⛔ 2026-10-07 实测教训：这里原先读 {@code spring.ai.openai.base-url/api-key} —— 那是
     * <b>第二个事实源</b>。聊天链由 {@code app.llm.providers} 驱动（ADR-58），视觉链却自己读 spring.ai，
     * 于是把聊天链切到 cline-pass 之后，<b>视觉这条静默留在 DeepSeek 官方 key 上</b>。
     * 「半迁移」在配置层看不出来，只有真去调视觉才会暴露 —— 所以现在与聊天链<b>同源</b>：
     * 取 {@code providers.primary()}（第一个 enabled 的 provider）。
     *
     * <p>模型：{@code app.llm.vision-model} <b>留空</b>则用主链模型（要求它是多模态的，
     * 例如 {@code cline-pass/deepseek-v4.1-flash} 实测可看图）；要视觉专用模型就设这个键。</p>
     */
    public VisionChatClient(LlmProviderProperties providers,
                            @Value("${app.llm.vision-model:}") String modelOverride,
                            AiTelemetry telemetry) {
        this.telemetry = telemetry;
        LlmProviderProperties.Provider p = providers.primary();
        if (p == null || p.getBaseUrl() == null || p.getBaseUrl().isBlank()) {
            // 不静默降级成"空端点"：宁可启动失败，也不要让视觉链悄悄指向一个不存在的地址
            throw new IllegalStateException(
                    "视觉链路需要至少一个 enabled 的 app.llm.providers（当前没有可用 provider）");
        }
        this.responseEnvelope = (p.getResponseEnvelope() == null || p.getResponseEnvelope().isBlank())
                ? null : p.getResponseEnvelope();
        this.model = (modelOverride == null || modelOverride.isBlank()) ? p.getModel() : modelOverride;
        this.completionsPath = (p.getCompletionsPath() == null || p.getCompletionsPath().isBlank())
                ? "/v1/chat/completions" : p.getCompletionsPath();
        // 与 curl 直连一致：{base}{completions-path}（cline.bot 的 base 是 /api，路径是 /v1/chat/completions）
        // 同步、长超时（视觉请求可达 30s+）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.restClient = RestClient.builder()
                .baseUrl(p.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + p.getApiKey())
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
        // ADR-62/F3：视觉链路绕过网关（既有 P3），观测上也**不存在** → 这里补上。
        // ⛔ 只记图片数量与结局，**绝不**记 prompt/图片内容（docs/07 §6.1「不含原文」）。
        Span span = telemetry.start("vision.call", telemetry.capture());
        span.tag("vision.images", String.valueOf(images == null ? 0 : images.size()));
        try (var ignored = telemetry.scope(span)) {
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
            // ⛔ 必须**按字节收、显式 UTF-8 解**：body(String.class) 在响应没带 charset 时
            //    会被 Spring 的 StringHttpMessageConverter 按 ISO-8859-1 解 —— 视觉模型回的是**中文**，
            //    于是整段变乱码（实测：`根上的回答` → `æ ¹ä¸çåç­`）。OpenAI 兼容端点一律 UTF-8。
            byte[] respBytes = restClient.post()
                    .uri(completionsPath)
                    .body(body.toString())
                    .retrieve()
                    .body(byte[].class);
            String respBody = respBytes == null ? "" : new String(respBytes, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(respBody);
            // ⛔ 少数网关把非流式响应包成 {"data": {...}}（cline.bot 实测）——
            //    不拆封会**静默返回空串**（root.path("choices") 取不到，asText("") 给空），
            //    现象是"模型什么都没说"，极难归因。
            if (responseEnvelope != null) {
                root = root.path(responseEnvelope);
            }
            span.tag("vision.outcome", "success");
            String text = root.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) {
                // 空正文要**看得见**：这是"信封没拆对 / 模型没吐字"的分界，不记一笔就查不出来。
                // ⛔ 只记形状，不记正文与图片内容（docs/07 §6.1）。
                log.warn("Vision chat returned blank content (envelope={}, model={}, bodyBytes={})",
                        responseEnvelope, model, respBody == null ? 0 : respBody.length());
            }
            return text;
        } catch (Exception e) {
            log.warn("Vision chat failed: {}", e.getMessage());
            span.tag("vision.outcome", "fail");
            telemetry.failure(span, e.getClass().getSimpleName());
            throw new cn.lwx.lwxaiagent.common.BizException(5000, "图片理解服务暂时不可用");
        }
        } finally {
            span.end();
        }
    }
}
