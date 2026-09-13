package cn.lwx.lwxaiagent.infrastructure.ai;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Small consecutive-failure circuit with a single half-open probe and stale-result fencing. */
public final class ProviderCircuit {
    private final int threshold;
    private final long openNanos;
    private final boolean enabled;
    private final LongSupplier clock;
    private int failures;
    private long openedAt;
    private long epoch;
    private boolean open;
    private boolean probing;

    public ProviderCircuit(boolean enabled, int threshold, long openMs) {
        this(enabled, threshold, openMs, System::nanoTime);
    }

    ProviderCircuit(boolean enabled, int threshold, long openMs, LongSupplier clock) {
        this.enabled = enabled;
        this.threshold = Math.max(1, threshold);
        this.openNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, openMs));
        this.clock = clock;
    }

    /** null means fail fast. Tickets must finish on success, error, or cancellation. */
    public synchronized Ticket acquire() {
        if (!enabled) return new Ticket(epoch, false);
        if (open) {
            if (probing || clock.getAsLong() - openedAt < openNanos) return null;
            probing = true;
            return new Ticket(epoch, true);
        }
        return new Ticket(epoch, false);
    }

    public final class Ticket {
        private final long generation;
        private final boolean probe;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Ticket(long generation, boolean probe) { this.generation = generation; this.probe = probe; }

        public void success() { finish(0); }
        public void failure() { finish(1); }
        public void cancel() { finish(2); }

        private void finish(int outcome) {
            if (!finished.compareAndSet(false, true)) return;
            synchronized (ProviderCircuit.this) {
                if (!enabled || generation != epoch) return;
                if (probe) {
                    probing = false;
                    if (outcome == 0) {
                        open = false;
                        failures = 0;
                        epoch++;
                    } else {
                        openedAt = clock.getAsLong();
                        epoch++;
                    }
                } else if (!open) {
                    if (outcome == 0) failures = 0;
                    if (outcome == 1 && ++failures >= threshold) {
                        open = true;
                        openedAt = clock.getAsLong();
                        epoch++;
                    }
                }
            }
        }
    }
}
