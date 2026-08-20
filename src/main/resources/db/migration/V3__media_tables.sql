-- ============================================================
-- V3: 图片多模态（ADR-11）
-- message_media：消息图片附件（上传后引用，随会话归属）
-- ============================================================

CREATE TABLE IF NOT EXISTS message_media (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    width INT DEFAULT 0,
    height INT DEFAULT 0,
    content_hmac CHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
);
