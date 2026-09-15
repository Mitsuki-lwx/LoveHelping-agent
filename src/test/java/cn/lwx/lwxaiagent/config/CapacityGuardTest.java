package cn.lwx.lwxaiagent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量口径一致性校验单测（Phase 6，ADR-29）。
 * 目的：防止"闸门高于网关并发"造成的隐性二次排队（用户侧表现为无提示的变慢）。
 */
class CapacityGuardTest {

    @Test
    void gateAboveGatewayFailsFast() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CapacityGuard(48, 24, 3000, 24));
        assertTrue(e.getMessage().contains("48"), "错误信息应含实际值，便于定位");
        assertTrue(e.getMessage().contains("24"));
    }

    @Test
    void gateEqualToGatewayIsValid() {
        assertDoesNotThrow(() -> new CapacityGuard(24, 24, 3000, 24));
    }

    @Test
    void gateBelowGatewayIsValid() {
        assertDoesNotThrow(() -> new CapacityGuard(8, 24, 3000, 8));
    }

    @Test
    void gatewayAboveMeasuredVendorLimitIsAllowedButWarned() {
        // 允许（可能已提升厂商配额），但构造过程不得失败
        assertDoesNotThrow(() -> new CapacityGuard(64, 64, 3000, 64));
    }
}
