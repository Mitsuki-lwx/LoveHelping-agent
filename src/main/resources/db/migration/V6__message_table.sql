-- ============================================================
-- V6: 消息表落库（Phase 2）
-- message：对话历史持久化真源，取代 SPRING_AI_CHAT_MEMORY（该表废弃）
--   - feedback    软反馈（NONE/LIKE/DISLIKE），免连表
--   - prompt_version 归因 System Prompt 版本（08 §2.4）
--   - deleted     软删（0=正常，1=已删），注销/清理不物理删（ADR-5 配套定时物理清除）
--   - content     明文工作列（Phase 2）；Phase 3 ADR-4 加密后迁移为 content_encrypted
--   - content_hmac  审计/去重预留（ADR-4 落地后与内容一致性校验）
-- ============================================================

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(50) NOT NULL DEFAULT 'anonymous',
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    content_hmac CHAR(64) DEFAULT NULL,
    prompt_version VARCHAR(16) DEFAULT NULL,
    feedback VARCHAR(8) NOT NULL DEFAULT 'NONE',
    deleted TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_created (conversation_id, created_at),
    INDEX idx_user (user_id)
);
