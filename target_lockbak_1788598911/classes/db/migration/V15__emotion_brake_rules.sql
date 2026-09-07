-- V15: 情绪刹车片规则（ADR-6 L2 降温类扩展）
-- 用于深夜时段（23:00-06:00）极端情绪表达拦截

INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, created_at)
VALUES
-- L2：极端情绪词（深夜触发时返回冷静提示）
('emotion_brake_extreme', 2, 'KEYWORD', '去死', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_2', 2, 'KEYWORD', '受不了', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_3', 2, 'KEYWORD', '想死', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_4', 2, 'KEYWORD', '完了', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_5', 2, 'KEYWORD', '废物', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_6', 2, 'KEYWORD', '分手', 1, '深夜极端情绪词', NOW()),
('emotion_brake_extreme_7', 2, 'REGEX', '(?i)(滚|滚开|滚蛋)', 1, '深夜极端情绪词（正则）', NOW()),
('emotion_brake_extreme_8', 2, 'KEYWORD', '恨你', 1, '深夜极端情绪词', NOW());
