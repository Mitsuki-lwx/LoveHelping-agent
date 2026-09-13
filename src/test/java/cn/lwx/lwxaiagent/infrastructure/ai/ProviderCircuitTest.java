package cn.lwx.lwxaiagent.infrastructure.ai;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCircuitTest {
    @Test void halfOpenAllowsOneProbeAndLateOldSuccessCannotCloseCircuit() {
        AtomicLong clock = new AtomicLong();
        var c = new ProviderCircuit(true, 1, 10, clock::get);
        var slow = c.acquire(); c.acquire().failure();
        slow.success(); assertNull(c.acquire());
        clock.set(11_000_000); var probe = c.acquire(); assertNotNull(probe); assertNull(c.acquire());
        probe.success(); assertNotNull(c.acquire());
    }
    @Test void cancelledProbeCannotLeaveCircuitStuckHalfOpen() {
        AtomicLong clock = new AtomicLong(); var c = new ProviderCircuit(true, 1, 10, clock::get);
        c.acquire().failure(); clock.set(11_000_000); c.acquire().cancel();
        assertNull(c.acquire()); clock.set(22_000_000); assertNotNull(c.acquire());
    }
}
