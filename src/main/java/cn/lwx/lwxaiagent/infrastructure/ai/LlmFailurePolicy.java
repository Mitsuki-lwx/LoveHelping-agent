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
