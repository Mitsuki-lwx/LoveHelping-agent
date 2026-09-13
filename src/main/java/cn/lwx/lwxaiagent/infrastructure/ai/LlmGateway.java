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
    private final Semaphore permits;
    private final ThreadPoolExecutor blocking;
    private final ProviderCircuit primaryCircuit;
    private final ProviderCircuit fallbackCircuit;
    private double retryTokens;
    private long refillNanos = System.nanoTime();

    @Autowired
    public LlmGateway(@Qualifier("openAiChatModel") ChatModel primary,
                      @Autowired(required = false) @Qualifier("deepSeekChatModel") ChatModel fallback,
                      LlmGatewayProperties props, MeterRegistry meters, AiTelemetry telemetry) {
        this.primary = primary;
        this.fallback = fallback;
        this.props = props;
        this.meters = meters;
        this.telemetry = telemetry;
        int max = Math.max(1, props.getMaxConcurrentCalls());
        this.permits = new Semaphore(max);
        this.blocking = new ThreadPoolExecutor(max, max, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("llm-call-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        blocking.allowCoreThreadTimeOut(true);
        var c = props.getCircuit();
        primaryCircuit = new ProviderCircuit(c.isEnabled(), c.getFailureThreshold(), c.getOpenMs());
        fallbackCircuit = new ProviderCircuit(c.isEnabled(), c.getFailureThreshold(), c.getOpenMs());
        retryTokens = props.getRetry().getBudgetPerMinute();
        meters.gauge("llm.inflight", permits, p -> max - p.availablePermits());
    }

    /** Explicit NOOP tracing for isolated unit tests. */
    public LlmGateway(ChatModel primary, ChatModel fallback, LlmGatewayProperties props, MeterRegistry meters) {
        this(primary, fallback, props, meters, new AiTelemetry(Tracer.NOOP));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getTotalTimeoutMs());
        TraceContext parent = telemetry.capture();
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
                if (!permits.tryAcquire()) throw new CapacityException();
                try (var ignored = telemetry.scope(span)) {
                    ChatResponse response = model.call(prompt);
                    if (!meaningful(response)) throw new EmptyResponseException();
                    return response;
                } finally { permits.release(); }
            });
            ChatResponse response = work.get(timeout, TimeUnit.MILLISECONDS);
            ticket.success();
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
                    .onErrorMap(this::publicFailure);
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
            if (!permits.tryAcquire()) { ticket.cancel(); return Flux.error(new CapacityException()); }
            Span span = attemptSpan(provider, attempt, parent);
            long start = System.nanoTime();
            AtomicBoolean content = new AtomicBoolean();
            AtomicReference<ChatResponse> lastUsage = new AtomicReference<>();
            AtomicReference<String> outcome = new AtomicReference<>("cancelled");
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> { if (released.compareAndSet(false, true)) permits.release(); };
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
                    .doOnComplete(() -> { ticket.success(); outcome.set("success"); release.run(); })
                    .doOnError(e -> {
                        if (fallbackAllowed(e)) ticket.failure(); else ticket.cancel();
                        outcome.set(e instanceof TimeoutException ? "timeout" : "fail");
                        release.run(); // Release before downstream retry/fallback subscribes.
                    })
                    .doFinally(signal -> {
                        ticket.cancel();
                        release.run();
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
