package cn.lwx.lwxaiagent.infrastructure.observability;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Langfuse >=3.22 OTLP HTTP uses Basic(publicKey:secretKey), not custom HMAC signing. */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.langfuse")
public class LangfuseProperties {
    private boolean enabled;
    private String publicKey;
    private String secretKey;
    private String host = "http://localhost:3000";
    @Min(1) @Max(65536) private int maxQueueSize = 2048;
    @Min(1) @Max(512) private int maxBatchSize = 256;
    @Min(1) private long scheduleDelayMs = 1000;
    @Min(1) private long timeoutMs = 3000;

    /**
     * 不导出 Spring Security 过滤链 span（默认开）。
     *
     * <p>为什么必须丢：这些 span（{@code secured request} / {@code authorize request} /
     * {@code security filterchain before|after}）是「根 span 被丢后剩下的孤儿」的唯一来源 ——
     * Prometheus 每 15s 抓一次 /actuator/prometheus，其**根 span 最后结束**，
     * 所以子 span 先落盘、那时根还没到、traceId 还没被记住 → 子 span 被放过去；
     * 稍后根到了被丢 → 这棵树永远没有根，在 Langfuse 里表现为**一条无名 trace**。
     * 实测（2026-09-24 A/B）:同窗口打 6 次 /actuator/prometheus 产生结构性无名碎片、
     * 打 6 次业务端点产生 0 条。</p>
     *
     * <p>代价（明确写下）：业务请求的 trace 里也会少这几条 span —— 它们在本项目里
     * **本来就没有可导出的属性**（导出白名单会把属性清空，实测 metadata.attributes = {}），
     * 属于纯噪声。要回滚：{@code LANGFUSE_DROP_SECURITY_SPANS=false}。</p>
     */
    private boolean dropSecurityFilterSpans = true;
}
