-- ============================================================
-- V4: 护栏升级（ADR-6）
-- guardrail_rule：外置规则（可配置，改动不发版）
-- guardrail_event：触发审计（只存 content_hmac，不存原文）
-- 梯度：L1 软提示 / L2 降温 / L3 硬阻断（自伤/伤人/违法/PUA/注入）
-- ============================================================

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,              -- 分类名（可多条同类，如 self_harm 多关键词）
    level TINYINT NOT NULL,                    -- 1/2/3
    pattern_type VARCHAR(16) NOT NULL,         -- KEYWORD（包含匹配）/ REGEX（正则 find）
    pattern VARCHAR(500) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_rule_id (rule_id)
);

CREATE TABLE IF NOT EXISTS guardrail_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) DEFAULT NULL,
    level TINYINT NOT NULL,
    rule_id VARCHAR(64) DEFAULT NULL,
    content_hmac CHAR(64) DEFAULT NULL,      -- 只存哈希，不存原文（07 §6）
    action VARCHAR(32) NOT NULL,             -- BLOCKED / LOGGED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created (created_at),
    INDEX idx_level (level)
);

-- ==================== 种子规则（人工维护，变更需双人复核） ====================

-- L3 自伤信号（转介，宁可误报不可漏报）
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description) VALUES
('self_harm', 3, 'KEYWORD', '自杀', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '自残', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '不想活', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '了结自己', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '结束生命', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '轻生', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '割腕', 1, '自伤信号-转介'),
('self_harm', 3, 'KEYWORD', '跳楼', 1, '自伤信号-转介');

-- L3 伤人/违法
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description) VALUES
('harm_others', 3, 'REGEX', '(?i)(想|要|计划)(杀|害|报复)(他|她|人)', 1, '伤人意图'),
('illegal', 3, 'KEYWORD', 'PUA教学', 1, 'PUA教学-阻断'),
('illegal', 3, 'REGEX', '(?i)(教|教教|教我).{0,6}(PUA|操控|拿捏|精神控制|打压|煤气灯)', 1, '操控教学-阻断'),
('illegal', 3, 'KEYWORD', '诈骗', 1, '违法内容');

-- L3 Prompt 注入（防注入必须，安全阻断）
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description) VALUES
('injection', 3, 'REGEX', '(?i)ignore\\s+(previous|prior|above|all)\\s+instructions?', 1, '提示注入'),
('injection', 3, 'REGEX', '(?i)(you\\s+are\\s+now|act\\s+as|pretend\\s+you\\s+are)\\s+(a|an)?\\s*(system|admin|developer)', 1, '提示注入'),
('injection', 3, 'REGEX', '(?i)(reveal|show|print)\\s+(your\\s+)?(system\\s+)?(prompt|instructions?)', 1, '提示注入'),
('injection', 3, 'REGEX', '(?i)忽略(以上|之前|前面|所有)(的)?(指令|提示|规则|设定)', 1, '提示注入'),
('injection', 3, 'REGEX', '(?i)DAN\\s+mode|jailbreak|越狱', 1, '提示注入');

-- L2 辱骂（记录不阻断）
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description) VALUES
('abuse', 2, 'KEYWORD', '傻逼', 1, '辱骂'),
('abuse', 2, 'KEYWORD', '废物', 1, '辱骂'),
('abuse', 2, 'KEYWORD', '去死', 1, '辱骂'),
('abuse', 2, 'REGEX', '(?i)(fuck|shit|bitch|asshole)', 1, '辱骂');

-- L1 模糊输入（记录不阻断，提示澄清）
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description) VALUES
('vague', 1, 'REGEX', '^[^，。？?]{1,15}$', 1, '过短输入');
