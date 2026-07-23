package cn.lwx.lwxaiagent.tenant.context;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @Test
    void setAndGet() {
        TenantContext.set("tenant1", "user1", "ADMIN");
        assertEquals("tenant1", TenantContext.getTenantId());
        assertEquals("user1", TenantContext.getUserId());
        assertEquals("ADMIN", TenantContext.getRole());
        TenantContext.clear();
    }

    @Test
    void clearRemovesValues() {
        TenantContext.set("t", "u", "r");
        TenantContext.clear();
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getRole());
    }

    @Test
    void defaultsAreNull() {
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getRole());
    }

    @Test
    void threadIsolation() throws InterruptedException {
        TenantContext.set("main", "mainUser", "USER");
        Thread t = new Thread(() -> {
            assertNull(TenantContext.getTenantId());
            TenantContext.set("thread", "threadUser", "ADMIN");
            assertEquals("thread", TenantContext.getTenantId());
        });
        t.start();
        t.join();
        assertEquals("main", TenantContext.getTenantId());
        TenantContext.clear();
    }
}
