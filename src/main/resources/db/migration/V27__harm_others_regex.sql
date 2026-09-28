-- V27：修 harm_others 的"了"字漏判（ADR-57）
--
-- 现象（离线复现）：pattern `(?i)(想|要|计划)(杀|害|报复)(他|她|人)` 要求**三词紧邻**，
--   而口语里"我想杀了他 / 我要害了她"极自然 → **中间多个"了"就漏** → **L3 安全漏判**。
--   实测：`我想杀了他` `我要杀了他` `我想害了她` `我真的想杀了他` 全部漏掉。
--
-- 这是**既有缺陷**（pattern 自 V4 起从未改动），由 ADR-56 的离线标定顺手发现。
--
-- 修法：**只加 `了?`**（保守）。
--   ⛔ 不放开中间任意字数（.{0,4}）—— 会命中"我想了解他"之外的更多正常表达，误报不可控。
--   ⛔ 不改成 `[了掉死]?` —— "杀死了/害死了"也可能是叙述往事，误报增量未评估。
--
-- ⚠️ 放宽正则会**同时**抬高召回与误报（同一枚硬币两面），两个数字都已在
--    docs/phase24-harm-others-regex/tasks.md §4 与 ADR-57 里量化。
--
-- ⚠️ 影响面：`harm_others` 在 V26 已降为 scope=INPUT → **只影响用户输入的拦截**；
--    输出侧的 `harm_others_incite`（建议类词形态）**不受影响**、本轮未改动。
--    scope 分布**不变**（仍 46 = 21/3/22）。

UPDATE guardrail_rule
   SET pattern = '(?i)(想|要|计划)(杀|害|报复)了?(他|她|人)'
 WHERE rule_id = 'harm_others';

-- 自检（期望 1 行，pattern 含 `了?`）
--   SELECT rule_id, scope, pattern FROM guardrail_rule WHERE rule_id='harm_others';
--
-- 回滚
--   UPDATE guardrail_rule SET pattern='(?i)(想|要|计划)(杀|害|报复)(他|她|人)'
--    WHERE rule_id='harm_others';
