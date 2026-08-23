-- V14: 沙盘专属记忆表（ADR-14 阶段 3，隔离存储）
-- 沙盘记忆独立于主聊天的 user_memory，不混淆

CREATE TABLE IF NOT EXISTS sandbox_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sandbox_id BIGINT NOT NULL COMMENT '沙盘会话 ID',
    user_id VARCHAR(50) NOT NULL,
    type VARCHAR(16) NOT NULL DEFAULT 'FACT' COMMENT 'FACT/SPEECH_STYLE/RELATION/EVENT',
    fact_text TEXT NOT NULL COMMENT '提取的事实或说话特征（脱敏后）',
    source_type VARCHAR(16) DEFAULT NULL COMMENT '来源：SCREENSHOT/PASTE/MANUAL',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sandbox_id (sandbox_id),
    INDEX idx_user_id (user_id)
);
