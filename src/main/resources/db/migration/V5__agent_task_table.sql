-- ============================================================
-- V5: Agent 任务持久化（ADR-3）
-- agent_task：任务生命周期落库（PENDING→RUNNING→SUCCESS/FAILED/CANCELLED）
-- 心跳补偿：RUNNING 且 heartbeat_at 超时 → 扫描标记 FAILED
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL DEFAULT 'default',
    user_id VARCHAR(50) NOT NULL,
    instruction TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    result_ref VARCHAR(255) DEFAULT NULL,
    error_code VARCHAR(32) DEFAULT NULL,
    error_msg VARCHAR(500) DEFAULT NULL,
    token_usage BIGINT DEFAULT 0,
    heartbeat_at DATETIME DEFAULT NULL,
    idempotency_key VARCHAR(64) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotency (idempotency_key),
    INDEX idx_user_status (user_id, status),
    INDEX idx_status_heartbeat (status, heartbeat_at)
);
