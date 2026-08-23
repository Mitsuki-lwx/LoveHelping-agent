-- V13: 沙盘合规护栏种子规则（ADR-12 三条硬规则）

INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, created_at)
VALUES
-- L3 阻断：请求扮演知名角色
('sandbox_known_ip', 3, 'REGEX', '(?i)扮演.{0,10}(原神|忍者|鸣人|米哈游|二次元|王者荣耀|漫威|海贼王|火影)', 1, '沙盘禁止扮演知名IP角色', NOW()),
-- L2 记录：角色称呼偏离（可选引导）
('sandbox_persona_drift', 2, 'REGEX', '(?i)(我(不是|不是的)|我是AI|我是人工智能|别假装)', 1, '沙盘角色漂移检测（L2记录，不阻断）', NOW());
