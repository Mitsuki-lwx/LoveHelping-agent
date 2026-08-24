-- V16: 对话洞察历史记录表（CAP-6 数据隐私，用户可管理）

CREATE TABLE IF NOT EXISTS insight_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    source_type VARCHAR(16) DEFAULT 'PASTE' COMMENT 'PASTE/SCREENSHOT',
    summary TEXT COMMENT '洞察摘要（简化存储，完整结果仍从分析结果生成）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);
