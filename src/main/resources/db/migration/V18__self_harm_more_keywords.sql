-- V18: 自伤/自杀 L3 关键词补充（2026-09-05 中危修复——'想死'等口语变体白天漏接）
-- 背景：V4 self_harm 仅有 自杀/自残/不想活/了结自己/结束生命；用户说"我想死/活不下去"
--      时 L3 不命中 → 白天无转介（"想死"只在 V15 情绪刹车词里、且仅深夜 L2 触发）。
-- 规则：rule_id=self_harm, level=3（全天硬阻断 + 转介话术，与 GuardrailAdvisor REFERRAL_TEXT 配套）。
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, created_at) VALUES
('self_harm', 3, 'KEYWORD', '想死', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '轻生', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '割腕', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '跳楼', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '活不下去', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '撑不下去', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '不想再醒', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '离开这个世界', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '伤害自己', 1, '自伤信号-转介', NOW()),
('self_harm', 3, 'KEYWORD', '结束自己', 1, '自伤信号-转介', NOW());
