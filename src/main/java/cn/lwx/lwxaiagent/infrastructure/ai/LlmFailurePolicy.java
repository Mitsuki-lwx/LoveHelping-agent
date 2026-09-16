package cn.lwx.lwxaiagent.infrastructure.ai;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/** Shared sync/stream classification. Unknown and programming errors are NOT retried. */
final class LlmFailurePolicy {
    private LlmFailurePolicy() {}

    static boolean cancelled(Throwable e) {
        for (Throwable t : causes(e)) {
            if (t instanceof InterruptedException || t instanceof CancellationException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    static boolean retryable(Throwable e) {
        if (cancelled(e)) return false;
        // Status has precedence over a generic SDK transient wrapper.
        for (Throwable t : causes(e)) {
            Integer status = status(t);
            if (status != null) return status == 408 || status == 429 || status >= 500;
        }
        for (Throwable t : causes(e)) {
            if (t instanceof IOException || t instanceof TimeoutException
                    || t instanceof TransientAiException || t instanceof EmptyResponseException) return true;
        }
        return false;
    }

    static boolean fallbackAllowed(Throwable e) {
        if (cancelled(e)) return false;
        if (e instanceof CircuitOpenException) return true;
        for (Throwable t : causes(e)) {
            Integer s = status(t);
            // Supplier auth/unavailable endpoint can fail over; malformed client requests cannot.
            if (s != null) return s == 401 || s == 403 || s == 404 || s == 408 || s == 429 || s >= 500;
        }
        return retryable(e);
    }

    /**
     * 厂商限流信号（ADR-32）：驱动并发闸门乘性收缩。
     *
     * <p>只认"被下游拒了"这一类证据，不认本地闸门拒绝（{@link CapacityException} 是自家队列满，
     * 收缩并发只会让本地更饿）、不认超时/连接失败（那是链路问题，不是配额问题）。</p>
     *
     * <p>识别两层：HTTP 429 直接判定；SDK 把错误体包成无状态码的
     * {@link TransientAiException} 时，退化为报文关键字匹配（bigmodel 的限流码是 1302）。</p>
     */
    static boolean throttled(Throwable e) {
        if (cancelled(e) || e instanceof CapacityException) return false;
        for (Throwable t : causes(e)) {
            Integer s = status(t);
            if (s != null && s == 429) return true;
        }
        for (Throwable t : causes(e)) {
            String message = t.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("429") || lower.contains("too many requests")
                    || lower.contains("rate limit") || lower.contains("rate_limit")
                    || lower.contains("concurrency limit") || lower.contains("concurrency_limit")) return true;
            // bigmodel 把限流包在错误体里（无 HTTP 状态码透出），错误码 1302 = 并发/速率超限。
            if (THROTTLE_CODE.matcher(message).find()) return true;
        }
        return false;
    }

    /** 匹配 {@code "code":"1302"} / {@code "code": 1302} 等错误体写法。 */
    private static final java.util.regex.Pattern THROTTLE_CODE =
            java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"?1302\"?");

    static long delayMs(Throwable e, int failedAttempt, LlmGatewayProperties.Retry p, long nowMs) {
        long exponential = (long) Math.min(p.getMaxBackoffMs(), p.getBackoffMs() * Math.pow(2, failedAttempt - 1));
        double jitter = p.getJitter();
        long delay = jitter <= 0 ? exponential : (long) (exponential * (1 - jitter + ThreadLocalRandom.current().nextDouble() * jitter));
        for (Throwable t : causes(e)) {
            HttpHeaders headers = headers(t);
            String raw = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (raw == null) continue;
            long advised;
            try {
                double seconds = Double.parseDouble(raw.trim());
                if (!Double.isFinite(seconds) || seconds < 0) continue;
                advised = (long) Math.min(Long.MAX_VALUE, seconds * 1000);
            } catch (NumberFormatException number) {
                try {
                    advised = Math.max(0, ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - nowMs);
                } catch (Exception ignored) { continue; }
            }
            // Never truncate Retry-After and retry EARLIER than the supplier requested.
            return Math.max(delay, advised);
        }
        return delay;
    }

    private static Integer status(Throwable t) {
        if (t instanceof WebClientResponseException e) return e.getStatusCode().value();
        if (t instanceof RestClientResponseException e) return e.getStatusCode().value();
        return null;
    }

    private static HttpHeaders headers(Throwable t) {
        if (t instanceof WebClientResponseException e) return e.getHeaders();
        if (t instanceof RestClientResponseException e) return e.getResponseHeaders();
        return null;
    }

    private static java.util.List<Throwable> causes(Throwable e) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.List<Throwable> out = new java.util.ArrayList<>();
        for (Throwable t = e; t != null && seen.add(t) && out.size() < 16; t = t.getCause()) out.add(t);
        return out;
    }

    static final class EmptyResponseException extends RuntimeException {}
    static final class CircuitOpenException extends RuntimeException {}
    static final class CapacityException extends RuntimeException {}
}
