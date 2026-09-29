package cn.lwx.lwxaiagent.config;

import cn.lwx.lwxaiagent.infrastructure.ai.LlmFallbackTier;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmGatewayProperties;
import cn.lwx.lwxaiagent.infrastructure.ai.LlmProviderChain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * <b>统一 LLM provider 装配（ADR-58）</b>：把 {@code app.llm.providers} 的每条配置
 * 变成一个有独立 WebClient / 熔断器 / 指标标签的 {@code ChatModel}。
 *
 * <p><b>取代了四个类</b>：{@code BigModelLastResortConfig}、{@code ClineFallbackConfig}、
 * {@code ClineApiCompatConfig}、{@code RestFallbackChatModel}（DashScope 原生协议）。
 * 现在接新供应商 = 在 yml 里加一条，Java 零改动。</p>
 *
 * <p><b>链路约定</b>：列表<b>第一条启用的</b>是主链（{@code llmPrimaryChatModel}），
 * 其余按顺序成为降级级（{@code List<LlmFallbackTier>}）。降级级名字即熔断器键，
 * 重名由 {@code LlmGateway} 在构造期拒绝。</p>
 *
 * <p><b>为什么 WebClient 自己建</b>：实测（2026-09-29）Spring AI 的 {@code OpenAiApi}
 * 不走容器的 {@code WebClient.Builder}（{@code WebClientCustomizer} 对它无效），
 * 所以"响应信封拆封"这类传输层差异<b>必须</b>在构建 provider 时直接挂上去 ——
 * 这正是把供应商差异降级为配置数据的前提。</p>
 *
 * <p><b>重试纪律</b>：底层 client 一律 {@code maxAttempts=1}，重试的唯一所有者是
 * {@code LlmGateway}（ADR-23）；否则会出现"两个重试所有者"，绕过闸门与熔断。</p>
 */
@Configuration
@EnableConfigurationProperties(LlmProviderProperties.class)
public class LlmProviderConfig {
    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);
    private static final String FIELD_CHOICES = "choices";

    /**
     * ADR-62 / F1：是否让 Spring AI **自己**再产生一条 {@code chat <model>} span。
     *
     * <p>默认 <b>false</b>（不产生）。原因（2026-09-29 实测）：那条 span 在流式路径上
     * <b>会自成一条 trace</b>，与业务 trace **1:1 双计**（冒烟窗口 12 业务 ↔ 11 孤儿）；
     * 而它携带的信息（model / usage）**我们的 {@code llm.attempt} 全都有**，纯冗余。
     * 置 true 即恢复（开关式回滚，无需改代码）。</p>
     */
    @Value("${app.llm.chat-observation:false}")
    private boolean chatObservation;

    /**
     * 整条链路（主链 + 降级级）由 {@code app.llm.providers} 装配。
     * 单一 bean 类型而非"动态条数的 tier bean"，理由见 {@link LlmProviderChain} 的类注释。
     */
    @Bean
    public LlmProviderChain llmProviderChain(LlmProviderProperties providers, LlmGatewayProperties gw,
                                             ObjectProvider<ObservationRegistry> observations,
                                             ObjectProvider<ToolCallingManager> toolManagers,
                                             ObjectMapper mapper) {
        LlmProviderProperties.Provider p = providers.primary();
        if (p == null) {
            throw new IllegalStateException(
                    "app.llm.providers 为空或全部 enabled=false —— 至少需要一条启用的 provider 作主链");
        }
        warnIfNoKey(p);
        log.info("[ADR-58] LLM 主链：name={} base={} model={} path={} envelope={}",
                p.getName(), p.getBaseUrl(), p.getModel(), p.getCompletionsPath(),
                p.getResponseEnvelope() == null ? "-" : p.getResponseEnvelope());

        List<LlmFallbackTier> tiers = providers.fallbacks().stream().map(f -> {
            warnIfNoKey(f);
            log.info("[ADR-58] LLM 降级级：name={} base={} model={} path={} envelope={}",
                    f.getName(), f.getBaseUrl(), f.getModel(), f.getCompletionsPath(),
                    f.getResponseEnvelope() == null ? "-" : f.getResponseEnvelope());
            return new LlmFallbackTier(f.getName(), build(f, gw, observations, toolManagers, mapper), f.getBaseUrl());
        }).toList();

        return new LlmProviderChain(build(p, gw, observations, toolManagers, mapper), p.getBaseUrl(), tiers);
    }

    private static void warnIfNoKey(LlmProviderProperties.Provider p) {
        if (!StringUtils.hasText(p.getApiKey())) {
            log.warn("[ADR-58] provider {} 未注入 api-key（{}）—— 调用期会失败，"
                    + "但按既有约定不阻断启动", p.getName(), p.getBaseUrl());
        }
    }

    /** 每条 provider 一个 OpenAiChatModel：自己的 WebClient（超时 + 可选信封拆封）+ 单次重试。 */
    private ChatModel build(LlmProviderProperties.Provider p, LlmGatewayProperties gw,
                                   ObjectProvider<ObservationRegistry> observations,
                                   ObjectProvider<ToolCallingManager> toolManagers,
                                   ObjectMapper mapper) {
        var apiBuilder = OpenAiApi.builder()
                .baseUrl(p.getBaseUrl())
                .apiKey(p.getApiKey() == null ? "" : p.getApiKey());
        if (StringUtils.hasText(p.getCompletionsPath())) {
            apiBuilder.completionsPath(p.getCompletionsPath());
        }
        apiBuilder.webClientBuilder(webClient(p, gw, mapper));
        apiBuilder.restClientBuilder(restClient(p, gw, mapper));
        OpenAiApi api = apiBuilder.build();

        // toolCallingManager 不能为 null（OpenAiChatModel 构造器直接断言）——传 null 会让应用起不来。
        // ADR-62/F1：默认把 observation 关掉（NOOP）——Spring AI 的 `chat <model>` span 在流式路径上
        // 自成一条 trace 造成双计，且信息与我们的 llm.attempt 重复。开关见 chatObservation。
        ObservationRegistry observationRegistry = chatObservation
                ? observations.getIfAvailable(() -> ObservationRegistry.NOOP)
                : ObservationRegistry.NOOP;
        ToolCallingManager toolCallingManager = toolManagers.getIfAvailable(
                () -> DefaultToolCallingManager.builder().observationRegistry(observationRegistry).build());

        return new OpenAiChatModel(api,
                OpenAiChatOptions.builder().model(p.getModel()).build(),
                toolCallingManager,
                RetryTemplate.builder().maxAttempts(1).build(),   // ADR-23：网关是唯一重试所有者
                observationRegistry);
    }

    private static WebClient.Builder webClient(LlmProviderProperties.Provider p, LlmGatewayProperties gw,
                                               ObjectMapper mapper) {
        long connectMs = p.getConnectTimeoutMs() != null ? p.getConnectTimeoutMs() : gw.getConnectTimeoutMs();
        var connector = new JdkClientHttpConnector(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs)).build());
        // 读超时取 total-timeout（不是 attempt-timeout）：流式的真实治理是网关的
        // firstByte / streamIdle / total 三档，这里只是 socket 级兜底。
        // 取 attempt-timeout 会把长回答的流式请求按"整次调用预算"砍掉。
        connector.setReadTimeout(Duration.ofMillis(gw.getTotalTimeoutMs()));
        WebClient.Builder builder = WebClient.builder()
                .clientConnector(connector)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024));
        if (StringUtils.hasText(p.getResponseEnvelope())) {
            builder.filter(unwrapEnvelope(p.getResponseEnvelope(), mapper));
        }
        return builder;
    }

    /**
     * 非流式走的是 {@code RestClient}（实测 2026-09-29：{@code OpenAiApi} 里
     * {@code chatCompletionEntity} 用 restClient、{@code chatCompletionStream} 用 webClient，
     * 两条传输路径各有一套 client）—— 所以信封拆封<b>必须两边都挂</b>，
     * 只挂 WebClient 会让 {@code LlmGateway.call()} 那条路依然解析失败（正是第一版的错）。
     */
    private static RestClient.Builder restClient(LlmProviderProperties.Provider p, LlmGatewayProperties gw,
                                                 ObjectMapper mapper) {
        long connectMs = p.getConnectTimeoutMs() != null ? p.getConnectTimeoutMs() : gw.getConnectTimeoutMs();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectMs));
        factory.setReadTimeout(Duration.ofMillis(gw.getTotalTimeoutMs()));
        return RestClient.builder().requestFactory(factory)
                .requestInterceptor(interceptor(p.getResponseEnvelope(), mapper));
    }

    /** 拦截器：读走响应体，命中信封则替换为内层 JSON。流式不经此路。 */
    static ClientHttpRequestInterceptor interceptor(String field, ObjectMapper mapper) {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            if (!StringUtils.hasText(field)) return response;
            byte[] raw = response.getBody().readAllBytes();
            String unwrapped = unwrap(new String(raw, StandardCharsets.UTF_8), field, mapper);
            if (unwrapped == null) return new BufferedResponse(response, raw);
            log.debug("[ADR-58] 已拆响应信封（非流式） field={}（{} → {} 字节）",
                    field, raw.length, unwrapped.length());
            return new BufferedResponse(response, unwrapped.getBytes(StandardCharsets.UTF_8));
        };
    }

    /** 让响应体可重复读的小包装（只在拆封路径上使用；长度会变，故同步修正 Content-Length）。 */
    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override public HttpStatusCode getStatusCode() throws IOException { return delegate.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return delegate.getStatusText(); }
        @Override public void close() { delegate.close(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }

        @Override public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(delegate.getHeaders());
            headers.setContentLength(body.length);   // 拆封后长度变了，必须改，否则解析器按旧长度截断
            return headers;
        }
    }

    /**
     * 响应信封拆封（可选）：只对<b>非 SSE</b> 响应体生效，且只在它确实是
     * {@code {"<field>":{...,"choices":[...]}}} 时才改写；其余（流式 / 错误体 / 普通 JSON）原样透传。
     */
    static ExchangeFilterFunction unwrapEnvelope(String field, ObjectMapper mapper) {
        return (request, next) -> next.exchange(request).flatMap(response -> {
            MediaType contentType = response.headers().asHttpHeaders().getContentType();
            if (contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                return Mono.just(response);   // 流式：一个字节都不碰
            }
            return response.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
                String unwrapped = unwrap(body, field, mapper);
                if (unwrapped == null) return response.mutate().body(body).build();
                log.debug("[ADR-58] 已拆响应信封 field={}（{} → {} 字节）",
                        field, body.length(), unwrapped.length());
                return response.mutate().body(unwrapped).build();
            });
        });
    }

    /** 命中信封形状返回内层 JSON；否则 {@code null}（调用方原样放行）。 */
    static String unwrap(String body, String field, ObjectMapper mapper) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode inner = root.get(field);
            if (inner != null && inner.isObject() && inner.has(FIELD_CHOICES)) {
                return mapper.writeValueAsString(inner);
            }
        } catch (Exception ignored) {
            // 非 JSON 或结构不符：原样放行 —— 兼容层本身绝不能成为故障源
        }
        return null;
    }
}
