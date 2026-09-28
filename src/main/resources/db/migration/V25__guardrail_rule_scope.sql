-- V25：护栏规则加"适用范围"维度（scope）—— 修 ADR-55 的 D2
--
-- 背景（实测，非推断）：
--   `check(String)` 一个方法同时被**输入侧**（GuardrailAdvisor / ChatEntry / SandboxController）
--   与**输出侧**（CheckNode）调用，而规则表**没有"适用范围"维度** →
--   同一套关键词在两个语义下被复用。
--
--   后果：输入侧「伤害自己」= 用户有风险（该转介）；
--         输出侧「你最近有没有想过伤害自己？」= **专业危机干预指导**（不该拦）。
--   实测（scripts/probe_l3_stream.py，2026-09-28）：3 组「如何帮助低落的朋友」的**正常求助**，
--   **6/6 轮**全部被输出侧判为 L3 —— 因为知识库里就有《自伤与自杀风险：如何回应并求助》，
--   助手引用它时必然带出这些词。
--
-- 修法：
--   1) 加 scope 列，默认 BOTH → **既有规则行为逐字不变**（输入侧回归底线）
--   2) self_harm 的裸关键词 → 降为 INPUT（输入侧仍拦；输出侧不再"提到就拦"）
--   3) 新增 self_harm_incite（OUTPUT）：输出侧只在**建议/教唆**语境下拦
--
-- ⚠️ 已知代价（写入 ADR-55）：输出侧改用正则词组合判语境，**必然漏判**某些教唆形态
--    （如"很多人最后都选了那条路"）。兜底 = 输入侧 + 词典。
-- ⚠️ 这是**放宽**输出侧的改动，必须与"流式拦截真的生效"（D1）同时上线 ——
--    只放宽不修机制 = 输出侧彻底不设防；只修机制不放宽 = 正常求助被误拦。

ALTER TABLE guardrail_rule ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'BOTH';

-- self_harm：裸关键词只保留在**输入侧**。
-- 覆盖 V4（8 条）与 V18（10 条）—— 用 rule_id 匹配，不枚举 pattern，避免漏改。
UPDATE guardrail_rule SET scope = 'INPUT' WHERE rule_id = 'self_harm';

-- 输出侧专用：**建议/教唆**语境才拦（"自伤词"必须与"建议/方法类词"同句相邻，≤16 字）。
-- 设计取舍：
--   · 不含"伤害自己"这类**询问/倾听**语境的词（"有没有想过…？"不应命中）
--   · `[^。！？\n]{0,16}` 限定**同句**，避免"…如何陪他？… 自杀风险"这种跨句误配
--   · 用 (?:) 非捕获组，`Pattern.find()` 语义
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, scope) VALUES
('self_harm_incite', 3, 'REGEX',
 '(?:你可以?|你不妨|不妨|不如|建议你?|教你|我来教你|方法|步骤|诀窍|怎样|如何|试试)[^。！？\\n]{0,16}(?:自杀|自残|结束自己|结束生命|了结自己|伤害自己|割腕|跳楼|轻生|想死|活不下去|离开这个世界)',
 1, '输出侧-教唆/建议自伤（仅 OUTPUT 生效）', 'OUTPUT');

-- 自检：确认改动落到预期行数（migration 失败比静默漏改好）
-- 期望：self_harm 全部为 INPUT
--   SELECT scope, COUNT(*) FROM guardrail_rule WHERE rule_id='self_harm' GROUP BY scope;
