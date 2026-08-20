-- ============================================================
-- V2: 记忆系统阶段 2（ADR-14）
-- user_memory：结构化事实记忆（用户可编辑）
-- conversation_summary：会话摘要（跨会话上下文压缩）
-- ============================================================

CREATE TABLE IF NOT EXISTS user_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    category VARCHAR(32) NOT NULL DEFAULT 'FACT',
    content VARCHAR(500) NOT NULL,
    confidence TINYINT NOT NULL DEFAULT 5,
    status VARCHAR(16) NOT NULL DEFAULT 'CANDIDATE',
    source_conversation_id VARCHAR(100) DEFAULT NULL,
    hit_count INT NOT NULL DEFAULT 0,
    last_hit_at DATETIME DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    is_edited BOOLEAN NOT NULL DEFAULT FALSE,
    ttl_days INT DEFAULT 180,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_status (user_id, status),
    INDEX idx_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS conversation_summary (
    conversation_id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    summary TEXT NOT NULL,
    summary_version INT NOT NULL DEFAULT 1,
    last_summarized_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);
