-- V30: 把「有害建议」关键词从 Java 硬编码搬进规则表（ADR-6「规则外置」+ 修流式不对称）
--
-- 背景（2026-10-01 核查）：
--   输出侧有两套判定机制 ——
--     ① guardrail_rule（DB，Scope.OUTPUT）→ **流式与非流式都会走**
--     ② OutputGuardrail 里硬编码的 HARMFUL_ADVICE_KEYWORDS → **只有非流式路径会拦**
--   而主路径是流式（SSE）⇒ 走流式时「报复他 / 你应该打」这类能直接出去。
--
--   同时，这套硬编码本身就偏离 ADR-6：「规则外置可配置（DB 表，**非硬编码正则**），
--   L3 关键词清单需人工复核」。
--
-- 决策：**把关键词搬进规则表**（scope=OUTPUT）。一举三得：
--   ① 回到 ADR-6 的规则外置原则；② StreamSink 自动覆盖流式；③ 两路径不再有第二套真相。
--
-- scope=OUTPUT 的理由：这些词针对的是**助手回复**（教唆/建议形态），不该拿它拦用户输入
--   （用户说「我想报复他」是在倾诉，输入侧由 harm_others 规则管，不该被本条误伤）。
--
-- level=3：与既有输出侧硬拦一致（命中即替换文案，不外发）。

INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, created_at, scope)
VALUES
('harmful_advice', 3, 'KEYWORD', '你应该打', 1, '输出有害建议-暴力', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '动手教训', 1, '输出有害建议-暴力', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '报复他', 1, '输出有害建议-报复', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '以牙还牙', 1, '输出有害建议-报复', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '跟踪她', 1, '输出有害建议-跟踪', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '查他手机', 1, '输出有害建议-侵犯隐私', NOW(), 'OUTPUT'),
('harmful_advice', 3, 'KEYWORD', '控制对方', 1, '输出有害建议-控制', NOW(), 'OUTPUT');
