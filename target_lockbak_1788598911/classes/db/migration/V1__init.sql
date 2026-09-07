-- ============================================================
-- V1: 初始基线（由 schema.sql 迁移而来，Flyway 接管 DDL）
-- 既有库通过 baseline-on-migrate 跳过本版本；全新库按本文件建表
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_vote (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    session_id VARCHAR(100) NOT NULL,
    message_index INT NOT NULL,
    vote_type VARCHAR(10) NOT NULL,
    feedback_text VARCHAR(500) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    source_session_id VARCHAR(100) DEFAULT NULL,
    entry_type VARCHAR(20) NOT NULL,
    label VARCHAR(10) NOT NULL DEFAULT 'GOOD',
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    tags VARCHAR(500) DEFAULT NULL,
    weight INT DEFAULT 1,
    quality_score TINYINT DEFAULT 5,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_conversations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    conversation_id VARCHAR(100) NOT NULL,
    title VARCHAR(200) DEFAULT NULL,
    chat_type VARCHAR(20) NOT NULL DEFAULT 'love',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_conv (user_id, conversation_id),
    INDEX idx_user_id (user_id),
    INDEX idx_user_conv_type (user_id, chat_type)
);

CREATE TABLE IF NOT EXISTS evolution_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    skill_name VARCHAR(200) NOT NULL,
    description VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    source_session_id VARCHAR(100) DEFAULT NULL,
    quality_score TINYINT DEFAULT 5,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_tenant_active (tenant_id, is_active),
    INDEX idx_source_session (source_session_id)
);

CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    `timestamp` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conv_id (conversation_id)
);
