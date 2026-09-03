package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * <h2>LlmGateway —— 多供应商 LLM 网关（ADR-7）</h2>
 *
 * <p>以 {@link ChatModel} 装饰器（FallbackChatModel）形式提供，包装主备两个模型：</p>
 * <ul>
 *   <li><b>主</b>：GoPlan（OpenAI 兼容端点，deepseek-v4-flash）</li>
 *   <li><b>备</b>：DeepSeek 官方（同模型，双供应商容灾——降级时语义不变）</li>
 * </ul>
 *
 * <p>职责：</p>
 * <ol>
 *   <li><b>重试</b>：主模型调用失败按指数退避重试（1s/2s/4s，仅首 token 前）</li>
 *   <li><b>降级</b>：主全败 → 切备模型；全失败抛 {@link BizException}（E5000 诚实报错）</li>
 *   <li><b>计量</b>：每次成功调用上报 token 用量（Micrometer counter {@code llm.tokens}）</li>
 * </ol>
 *
 * <p>作为 {@code @Primary ChatModel} 注册（见 {@code ChatModelConfig}），
 * 主聊天管道（LoveApp/MemoryExtractor）自动获得网关能力，消费者零改动；
 * 显式 {@code @Qualifier("deepSeekChatModel")} 的后台任务保持直连，失败不致命无需降级。</p>
 *
 * <p><b>约束（ADR-7）</b>：无 LLM 响应缓存、无本地语料兜底；流式连接建立后中断不重试主模型
 * （避免重复输出），仅做故障转移切备。</p>
 */
@Slf4j
@Component
public class LlmGateway implements ChatModel {

    /** 主供应商（GoPlan） */
    private final ChatModel primary;
    /** 备用供应商（DeepSeek 官方） */
    private final ChatModel fallback;
    private final LlmGatewayProperties props;
    private final MeterRegistry meterRegistry;
    /** token 计量 counter：标签 provider / type(prompt|completion) */
    private final Counter promptTokensCounter;
    private final Counter completionTokensCounter;

    public LlmGateway(@Qualifier("openAiChatModel") ChatModel primary,
                      @org.springframework.beans.factory.annotation.Autowired(required = false) @Qualifier("deepSeekChatModel") ChatModel fallback,
                      LlmGatewayProperties props,
                      MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.promptTokensCounter = Counter.builder("llm.tokens")
                .tag("type", "prompt")
                .description("LLM prompt tokens consumed")
                .register(meterRegistry);
        this.completionTokensCounter = Counter.builder("llm.tokens")
                .tag("type", "completion")
                .description("LLM completion tokens consumed")
                .register(meterRegistry);
    }

    // ==================== call（同步） ====================

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            ChatResponse response = callWithRetry(primary, prompt, "primary");
            recordUsage(response, "sensenova");
            recordCall("success", "sensenova");
            return response;
        } catch (RuntimeException primaryEx) {
            recordCall("fallback", "sensenova");
            if (!props.isFallbackEnabled() || fallback == null) {
                recordCall("fail", "sensenova");
                log.error("LLM primary failed, no fallback available: {}", primaryEx.getMessage());
                throw new BizException(5000, "AI 服务暂时不可用，请稍后再试");
            }
            log.warn("LLM primary failed after retries, switching to fallback: {}",
                    primaryEx.getMessage());
            try {
                ChatResponse response = fallback.call(prompt);
                recordUsage(response, "deepseek");
                recordCall("success", "deepseek");
                return response;
            } catch (RuntimeException fallbackEx) {
                recordCall("fail", "deepseek");
                log.error("LLM all providers failed: primary={}, fallback={}",
                        primaryEx.getMessage(), fallbackEx.getMessage());
                throw new BizException(5000, "AI 服务暂时不可用，请稍后再试");
            }
        }
    }

    private ChatResponse callWithRetry(ChatModel model, Prompt prompt, String name) {
        int max = Math.max(1, props.getRetry().getMaxAttempts());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                ChatResponse response = model.call(prompt);
                // 空回复防御（2026-09-02 实测 glm-4.7 偶发返回空 content 且不抛异常）：
                // 空输出视为失败进入重试/降级，避免把空回答当成功回给用户
                if (isEmptyResponse(response)) {
                    log.warn("LLM {} returned empty content (attempt {}/{}), treating as failure",
                            name, attempt, max);
                    throw new EmptyContentException("LLM " + name + " returned empty content");
                }
                return response;
            } catch (RuntimeException e) {
                // 不可重试的 4xx（参数/鉴权错误等）重试无意义：直接失败（外层走降级/报错）
                if (!isRetryable(e)) {
                    log.warn("LLM {} call failed with non-retryable error (attempt {}/{}): {}",
                            name, attempt, max, e.getMessage());
                    throw e;
                }
                last = e;
                log.warn("LLM {} call attempt {}/{} failed: {}", name, attempt, max, e.getMessage());
                if (attempt < max) {
                    sleepQuietly(retryDelayMs(e, attempt));
                }
            }
        }
        throw last;
    }

    /** 429 尊重 Retry-After 的封顶值（秒）——不让单次用户请求被拖死 */
    private static final long MAX_RETRY_AFTER_SEC = 30;

    /**
     * 是否值得重试：429（限流）、5xx（服务端）、网络/超时、空回复——可重试；
     * 4xx（400/401/403/404/422 等参数与鉴权问题）不可重试（2026-09-03 增强）。
     */
    private boolean isRetryable(RuntimeException e) {
        if (e instanceof EmptyContentException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                int status = wcre.getStatusCode().value();
                return status >= 500 || status == 429;
            }
            if (t instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                int status = hsce.getStatusCode().value();
                return status >= 500 || status == 429;
            }
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.ConnectException
                    || t instanceof java.net.http.HttpTimeoutException) {
                return true; // 网络/超时
            }
        }
        return true; // 未知异常默认可重试（保守，宁可重试也不直接放弃）
    }

    /** 本次重试前的等待：429 且带 Retry-After 用其值（封顶 30s），否则指数退避 */
    private long retryDelayMs(RuntimeException e, int attempt) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            try {
                String retryAfter = null;
                if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wcre) {
                    var h = wcre.getHeaders();
                    if (h != null) {
                        retryAfter = h.getFirst("Retry-After");
                    }
                } else if (t instanceof org.springframework.web.client.HttpStatusCodeException hsce) {
                    var h = hsce.getResponseHeaders();
                    if (h != null) {
                        retryAfter = h.getFirst("Retry-After");
                    }
                }
                if (retryAfter != null && !retryAfter.isBlank()) {
                    try {
                        long sec = (long) Double.parseDouble(retryAfter.trim());
                        return Math.min(sec, MAX_RETRY_AFTER_SEC) * 1000;
                    } catch (NumberFormatException ignored) {
                        // Retry-After 为 HTTP-date 时忽略，退回指数退避
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return props.getRetry().getBackoffMs() * (1L << (attempt - 1));
    }

    /** 空回复判定：无 choices / 无 content 文本 / content 全空白。
     *  <p><b>例外</b>：agent 工具循环中模型可只返回 {@code tool_calls} 而 content 为空——
     *  这是正常形态（驱动工具调用），不算空回复（2026-09-02 实测误伤 agent 图后修正）。</p> */
    private boolean isEmptyResponse(ChatResponse response) {
        try {
            if (response == null || response.getResult() == null) {
                return true;
            }
            var output = response.getResult().getOutput();
            if (output == null) {
                return true;
            }
            String text = output.getText();
            boolean hasText = text != null && !text.isBlank();
            if (!hasText && output instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
                var toolCalls = am.getToolCalls();
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    return false; // 纯 tool_calls 响应（agent 工具循环）
                }
            }
            return !hasText;
        } catch (Exception e) {
            return false; // 判定失败按非空处理，避免误伤
        }
    }

    /** 空回复专用异常（触发上层重试/降级，不直接抛给用户） */
    private static final class EmptyContentException extends RuntimeException {
        EmptyContentException(String message) {
            super(message);
        }
    }

    // ==================== stream（流式） ====================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Flux<ChatResponse> primaryStream = Flux.defer(() -> primary.stream(prompt));
        if (!props.isFallbackEnabled() || fallback == null) {
            return primaryStream
                    .doOnNext(r -> recordUsage(r, "sensenova"))
                    .doOnComplete(() -> recordCall("success", "sensenova"));
        }
        return primaryStream
                .doOnNext(r -> recordUsage(r, "sensenova"))
                .doOnComplete(() -> recordCall("success", "sensenova"))
                .onErrorResume(e -> {
                    log.warn("LLM primary stream failed, switching to fallback: {}", e.getMessage());
                    return Flux.defer(() -> fallback.stream(prompt))
                            .doOnNext(r -> recordUsage(r, "deepseek"))
                            .doOnComplete(() -> recordCall("fallback", "goplan"));
                });
    }

    // ==================== 默认选项 ====================

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }

    // ==================== 计量 ====================

    private void recordUsage(ChatResponse response, String provider) {
        try {
            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                if (usage.getPromptTokens() != null) {
                    promptTokensCounter.increment(usage.getPromptTokens());
                }
                if (usage.getCompletionTokens() != null) {
                    completionTokensCounter.increment(usage.getCompletionTokens());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to record LLM usage: {}", e.getMessage());
        }
    }

    /** 上报调用计数（08 §2.2 llm.call：provider × outcome）。 */
    private void recordCall(String outcome, String provider) {
        try {
            Counter.builder("llm.call")
                    .tag("provider", provider)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
        } catch (Exception e) {
            log.debug("Failed to record LLM call metric: {}", e.getMessage());
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
