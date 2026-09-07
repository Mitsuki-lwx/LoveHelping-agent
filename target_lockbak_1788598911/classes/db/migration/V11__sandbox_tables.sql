-- V11: 沙盘模拟表（ADR-9/ADR-12，Phase 4）

-- 沙盘角色原型库（原创，遵守 ADR-12 版权三条硬规则）
CREATE TABLE IF NOT EXISTS sandbox_persona (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    archetype VARCHAR(32) NOT NULL,
    traits_json TEXT NOT NULL COMMENT 'JSON: {tone, catchphrase, relationshipStage, ...}',
    avatar_url VARCHAR(255) DEFAULT NULL,
    is_custom BOOLEAN DEFAULT FALSE COMMENT '是否为用户自定义（仅自定义用户可编辑）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_archetype (archetype)
);

-- 沙盘会话（每个会话=一次沙盘模拟对话）
CREATE TABLE IF NOT EXISTS sandbox_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'REALISTIC' COMMENT 'REALISTIC / ANIME',
    persona_id BIGINT DEFAULT NULL,
    custom_traits TEXT DEFAULT NULL COMMENT '用户自定义特征（≤200字，LLM归一化后存）',
    history_version INT DEFAULT 0,
    drift_count INT DEFAULT 0 COMMENT '连续漂移次数（ADR-9，>=3 触发用户确认）',
    needs_user_confirm TINYINT DEFAULT 0 COMMENT '漂移检测标记：1=需用户确认后重置',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_channel (user_id, channel),
    INDEX idx_last_active (last_active_at)
);
