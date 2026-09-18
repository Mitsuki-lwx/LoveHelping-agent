package cn.lwx.lwxaiagent.infrastructure.ai;

import cn.lwx.lwxaiagent.common.BizException;
import cn.lwx.lwxaiagent.infrastructure.observability.AiTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static cn.lwx.lwxaiagent.infrastructure.ai.LlmFailurePolicy.*;

/** ADR-23: one retry owner, finite budgets, provider circuits, and no replay after streamed output. */
@Component
public class LlmGateway implements ChatModel {
    private final ChatModel primary;
    private final ChatModel fallback;
    private final LlmGatewayProperties props;
    private final MeterRegistry meters;
    private final AiTelemetry telemetry;
    private final AdaptiveConcurrencyLimiter limiter;
    private final ThreadPoolExecutor blocking;
    private final ProviderCircuit primaryCircuit;
    private final ProviderCircuit fallbackCircuit;
    private final ApplicationEventPublisher events;
    private double retryTokens;
    private long refillNanos = System.nanoTime();

    @Autowired
    public LlmGateway(@Qualifier("openAiChatModel") ChatModel primary,
                      @Autowired(required = false) @Qualifier("deepSeekChatModel") ChatModel fallback,
                      LlmGatewayProperties props, MeterRegistry meters, AiTelemetry telemetry,
                      ApplicationEventPublisher events) {
        this.primary = primary;
        this.fallback = fallback;
        this.props = props;
        this.meters = meters;
        this.telemetry = telemetry;
        this.events = events;
        int max = Math.max(1, props.getMaxConcurrentCalls());
        this.limiter = new AdaptiveConcurrencyLimiter(max, props.getAdaptive());
        this.blocking = new ThreadPoolExecutor(max, max, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("llm-call-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        blocking.allowCoreThreadTimeOut(true);
        var c = props.getCircuit();
        primaryCircuit = new ProviderCircuit(c);
        fallbackCircuit = new ProviderCircuit(c);
        retryTokens = props.getRetry().getBudgetPerMinute();
        meters.gauge("llm.inflight", limiter, AdaptiveConcurrencyLimiter::inflight);
        // ADR-32: 闸门不再是固定值，暴露自适应收敛结果便于观测"厂商现在能容忍多少"。
        meters.gauge("llm.permits.limit", limiter, AdaptiveConcurrencyLimiter::limit);
    }

    /** Explicit NOOP tracing for isolated unit tests. */
    public LlmGateway(ChatModel primary, ChatModel fallback, LlmGatewayProperties props, MeterRegistry meters) {
        this(primary, fallback, props, meters, new AiTelemetry(Tracer.NOOP), event -> { });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getTotalTimeoutMs());
        TraceContext parent = telemetry.capture();
        // ADR-31 发现二：并发许可覆盖"重试 + 降级"整条链路，而不是每次尝试各借还一次。
        // 原来的写法会在两次尝试之间留下准入空窗——凭证被放回池中可能立即易主，
        // 而厂商侧那次调用未必已经结束，于是瞬时在途会突破收敛后的容量口径；
        // 本次请求自己的重试也可能因别人取走凭证而被自家 4003 拒掉。
        // 必须经 publicFailure 映射：调用方（如 SkillReflector）靠 BizException(4003) 识别
        // "自家闸门满"这一预期结果；裸 CapacityException 会被它当成真故障打 ERROR 全栈（回归）。
        if (!limiter.tryAcquire()) throw publicFailure(new CapacityException());
        try {
            return callWithRetries(prompt, deadline, parent);
        } finally {
            limiter.release();
        }
    }

    private ChatResponse callWithRetries(Prompt prompt, long deadline, TraceContext parent) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= props.getRetry().getMaxAttempts(); attempt++) {
            try {
                return syncAttempt(primary, primaryCircuit, prompt, "primary", attempt, deadline, parent);
            } catch (RuntimeException e) {
                failure = e;
                if (cancelled(e)) throw cancellation();
                long delay = delayMs(e, attempt, props.getRetry(), System.currentTimeMillis());
                if (!retryable(e) || !canRetry(attempt, delay, deadline)) break;
                metric("llm.retry", "primary", "scheduled");
                try { Thread.sleep(delay); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw cancellation(); }
            }
        }
        if (canFallback(failure) && remainingMs(deadline) > 0) {
            metric("llm.fallback", "primary", "started");
            try { return syncAttempt(fallback, fallbackCircuit, prompt, "fallback", 1, deadline, parent); }
            catch (RuntimeException e) { failure = e; }
        }
        throw publicFailure(failure);
    }

    private ChatResponse syncAttempt(ChatModel model, ProviderCircuit circuit, Prompt prompt, String provider,
                                     int attempt, long deadline, TraceContext parent) {
        if (Thread.currentThread().isInterrupted()) throw cancellation();
        long timeout = Math.min(props.getAttemptTimeoutMs(), remainingMs(deadline));
        if (timeout <= 0) throw new CompletionException(new TimeoutException());
        ProviderCircuit.Ticket ticket = circuit.acquire();
        if (ticket == null) { metric("llm.circuit", provider, "rejected"); throw new CircuitOpenException(); }
        Span span = attemptSpan(provider, attempt, parent);
        long start = System.nanoTime();
        Future<ChatResponse> work = null;
        String outcome = "fail";
        try {
            work = blocking.submit(() -> {
                // 并发许可由 call() 在整个链路外层统一持有（ADR-31 发现二），worker 内不再借还。
                try (var ignored = telemetry.scope(span)) {
                    ChatResponse response = model.call(prompt);
                    if (!meaningful(response)) throw new EmptyResponseException();
                    return response;
                }
            });
            ChatResponse response = work.get(timeout, TimeUnit.MILLISECONDS);
            ticket.success();
            if (limiter.onSuccess()) adaptive(provider, "grow");
            usage(response, provider, span);
            outcome = "success";
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ticket.cancel();
            outcome = "cancelled";
            throw cancellation();
        } catch (TimeoutException e) {
            ticket.failure();
            outcome = "timeout";
            throw new CompletionException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            RuntimeException failure = cause instanceof RuntimeException r ? r : new CompletionException(cause);
            if (fallbackAllowed(failure)) ticket.failure(); else ticket.cancel();
            if (throttled(failure) && limiter.onThrottled()) adaptive(provider, "shrink");
            outcome = cancelled(failure) ? "cancelled" : "fail";
            throw failure;
        } catch (RejectedExecutionException e) {
            ticket.cancel();
            outcome = "rejected";
            throw new CapacityException();
        } finally {
            if (work != null && !work.isDone()) work.cancel(true);
            // The worker (not Future completion) releases its permit, even for an uninterruptible SDK.
            ticket.cancel();
            finish(span, provider, outcome, start);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        TraceContext captured = telemetry.capture();
        return Flux.deferContextual(context -> {
            // ADR-31 发现二：并发许可在订阅入口获取一次，覆盖该请求的全部重试与降级尝试，
            // 直到整条流终止（complete / error / cancel）才归还。此前每次尝试各借还一次，
            // 尝试之间的空窗会让凭证易主，厂商侧瞬时在途因此可突破容量上限。
            if (!limiter.tryAcquire()) return Flux.error(publicFailure(new CapacityException()));
            AtomicBoolean permitReleased = new AtomicBoolean();
            Runnable releasePermit = () -> { if (permitReleased.compareAndSet(false, true)) limiter.release(); };
            TraceContext parent = context.getOrDefault(AiTelemetry.PARENT_CONTEXT_KEY, captured);
            AtomicBoolean emitted = new AtomicBoolean();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getTotalTimeoutMs());
            return primaryStream(prompt, 1, emitted, deadline, parent)
                    .onErrorResume(e -> {
                        if (!emitted.get() && canFallback(e) && remainingMs(deadline) > 0) {
                            metric("llm.fallback", "primary", "started");
                            return streamAttempt(fallback, fallbackCircuit, prompt, "fallback", 1, emitted, parent);
                        }
                        return Flux.error(e);
                    })
                    .takeUntilOther(Mono.delay(Duration.ofMillis(props.getTotalTimeoutMs()))
                            .flatMap(t -> Mono.error(new TimeoutException("LLM total deadline exceeded"))))
                    .onErrorMap(this::publicFailure)
                    .doFinally(signal -> releasePermit.run());
        });
    }

    private Flux<ChatResponse> primaryStream(Prompt prompt, int attempt, AtomicBoolean emitted,
                                            long deadline, TraceContext parent) {
        return streamAttempt(primary, primaryCircuit, prompt, "primary", attempt, emitted, parent)
                .onErrorResume(e -> {
                    long delay = delayMs(e, attempt, props.getRetry(), System.currentTimeMillis());
                    if (!emitted.get() && retryable(e) && canRetry(attempt, delay, deadline)) {
                        metric("llm.retry", "primary", "scheduled");
                        return Mono.delay(Duration.ofMillis(delay))
                                .thenMany(primaryStream(prompt, attempt + 1, emitted, deadline, parent));
                    }
                    return Flux.error(e);
                });
    }

    private Flux<ChatResponse> streamAttempt(ChatModel model, ProviderCircuit circuit, Prompt prompt,
                                            String provider, int attempt, AtomicBoolean emitted, TraceContext parent) {
        return Flux.defer(() -> {
            ProviderCircuit.Ticket ticket = circuit.acquire();
            if (ticket == null) { metric("llm.circuit", provider, "rejected"); return Flux.error(new CircuitOpenException()); }
            // 并发许可不在此处借还：由 stream() 在整条链路外层统一持有（ADR-31 发现二）。
            Span span = attemptSpan(provider, attempt, parent);
            long start = System.nanoTime();
            AtomicBoolean content = new AtomicBoolean();
            AtomicReference<ChatResponse> lastUsage = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>("cancelled");
            return Flux.defer(() -> {
                        try (var ignored = telemetry.scope(span)) { return model.stream(prompt); }
                    })
                    .timeout(Mono.delay(Duration.ofMillis(props.getAttemptTimeoutMs())),
                            r -> Mono.delay(Duration.ofMillis(props.getStreamIdleTimeoutMs())))
                    .doOnNext(r -> {
                        emitted.set(true); // Any emitted response commits the stream; never replay prefixes/tool calls.
                        if (meaningful(r)) content.set(true);
                        if (hasUsage(r)) lastUsage.set(r);
                    })
                    .concatWith(Flux.defer(() -> content.get() ? Flux.empty() : Flux.error(new EmptyResponseException())))
                    .doOnComplete(() -> {
                        ticket.success();
                        outcome.set("success");
                        if (limiter.onSuccess()) adaptive(provider, "grow");
                    })
                    .doOnError(e -> {
                        if (fallbackAllowed(e)) ticket.failure(); else ticket.cancel();
                        if (throttled(e) && limiter.onThrottled()) adaptive(provider, "shrink");
                        outcome.set(e instanceof TimeoutException ? "timeout" : "fail");
                    })
                    .doFinally(signal -> {
                        ticket.cancel();
                        usage(lastUsage.get(), provider, span); // Cumulative usage is recorded ONCE, not per chunk.
                        finish(span, provider, outcome.get(), start);
                    });
        });
    }

    private boolean canRetry(int failedAttempt, long delay, long deadline) {
        return failedAttempt < props.getRetry().getMaxAttempts()
                && delay <= props.getRetry().getMaxBackoffMs() && delay < remainingMs(deadline)
                && takeRetryToken();
    }

    private synchronized boolean takeRetryToken() {
        long now = System.nanoTime();
        int capacity = props.getRetry().getBudgetPerMinute();
        retryTokens = Math.min(capacity, retryTokens + (now - refillNanos) / 60_000_000_000.0 * capacity);
        refillNanos = now;
        if (retryTokens < 1) { metric("llm.retry", "primary", "budget_rejected"); return false; }
        retryTokens--;
        return true;
    }

    private boolean canFallback(Throwable e) {
        return props.isFallbackEnabled() && fallback != null && e != null && fallbackAllowed(e);
    }

    /**
     * 闸门收敛值发生变化时：记指标 + 广播给准入层（ADR-32）。
     *
     * <p>准入层必须跟随同一个收敛值，否则"网关放行 24、实际只跑 15"会让多出的请求
     * 变成自家 4003——厂商的 429 只是被换成自家拒绝，用户可见失败率反而升高。</p>
     */
    private void adaptive(String provider, String outcome) {
        metric("llm.adaptive", provider, outcome);
        events.publishEvent(new CapacityLimitChanged(limiter.limit()));
    }

    private RuntimeException publicFailure(Throwable e) {
        if (cancelled(e)) return cancellation();
        if (e instanceof BizException b) return b;
        if (e instanceof CapacityException) return new BizException(4003, "AI 服务繁忙，请稍后再试", java.util.Map.of("retryAfterSec", 2));
        return new BizException(5000, "AI 服务暂时不可用，请稍后再试");
    }

    private static CancellationException cancellation() { return new CancellationException("LLM request cancelled"); }
    private long remainingMs(long deadline) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    private boolean meaningful(ChatResponse r) {
        if (r == null || r.getResult() == null || r.getResult().getOutput() == null) return false;
        var output = r.getResult().getOutput();
        return (output.getText() != null && !output.getText().isBlank())
                || (output.getToolCalls() != null && !output.getToolCalls().isEmpty());
    }

    private boolean hasUsage(ChatResponse r) { return r != null && r.getMetadata() != null && r.getMetadata().getUsage() != null; }
    private void usage(ChatResponse r, String provider, Span span) {
        if (!hasUsage(r)) return;
        var usage = r.getMetadata().getUsage();
        int input = usage.getPromptTokens() == null ? 0 : Math.max(0, usage.getPromptTokens());
        int output = usage.getCompletionTokens() == null ? 0 : Math.max(0, usage.getCompletionTokens());
        meters.counter("llm.tokens", "provider", provider, "type", "prompt").increment(input);
        meters.counter("llm.tokens", "provider", provider, "type", "completion").increment(output);
        span.tag("langfuse.observation.usage_details", "{\"input\":" + input + ",\"output\":" + output + "}");
        if (r.getMetadata().getModel() != null) span.tag("langfuse.observation.model.name", r.getMetadata().getModel());
    }

    private Span attemptSpan(String provider, int attempt, TraceContext parent) {
        Span span = telemetry.start("llm.attempt", parent);
        span.tag("langfuse.observation.type", "generation");
        span.tag("llm.provider", provider);
        span.tag("llm.attempt", String.valueOf(attempt));
        return span;
    }
    private void finish(Span span, String provider, String outcome, long start) {
        try {
            span.tag("llm.outcome", outcome);
            if (!"success".equals(outcome) && !"cancelled".equals(outcome)) telemetry.failure(span, outcome);
            metric("llm.call", provider, outcome);
            meters.timer("llm.latency", "provider", provider, "outcome", outcome)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        } finally { span.end(); }
    }
    private void metric(String name, String provider, String outcome) {
        meters.counter(name, "provider", provider, "outcome", outcome).increment();
    }
    @Override public ChatOptions getDefaultOptions() { return primary.getDefaultOptions(); }
    @PreDestroy public void close() { blocking.shutdownNow(); }
}
