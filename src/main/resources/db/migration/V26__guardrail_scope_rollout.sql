-- V26：护栏 scope 维度推广到 illegal / harm_others（补 ADR-55 的 D2 缺口）
--
-- 背景（**实测，非推断**）：
--   ADR-55 只把 `self_harm` 的裸词降为 INPUT，但**同形缺陷在 illegal / harm_others 上还在**。
--   实测（scripts/run_l3_trigger.sh，2026-09-28 17:24）：
--     输入「怎么识别那种只图钱不谈感情的人？」→ 助手给出**正常的防骗科普**，
--     输出里自然出现裸词「诈骗」→ 命中 illegal（scope=BOTH）→ **被拦** →
--     用户拿到"这个话题涉及的内容我不能帮你处理…" = **剥夺了有用信息**。
--
-- 修法（与 V25 同形）：裸词 / 输入侧请求形态 → INPUT；另建输出侧"教唆形态"规则。
--
-- ⚠️ 为什么用精确条件而不是 `WHERE rule_id='illegal'`：
--   `PUA教学` 是**请求用语**（用户才会说"给我 PUA 教学"），助手输出里几乎不会自然出现，
--   误伤风险低，且它确实是"教唆"语义 → **保留 BOTH**。
--   全按 rule_id 降会让"助手输出 PUA教学：…"漏过（虽然概率低）。
--
-- ⚠️ `harm_others` 的请求形态 `(想|要|计划)(杀|害|报复)(他|她|人)` 在**输入侧**是对的
--   （用户表达伤人意图 → L3），但在输出侧会误中**共情解读**
--   （"我想报复他**这句话背后**，通常是被深深伤害后的无力感" — 离线标定实测误报）。
--
-- ⛔ 反向风险：降 INPUT 后输出侧对这些语义**完全没防护**，所以下面必须补 incite 规则。
--    只降不补 = 把门全开（tasks.md §4 的 J2 就是为了防这个）。

-- ① 裸词 `诈骗` + 请求形态 `(教|教教|教我).{0,6}(PUA|操控|…)` → 输入侧
UPDATE guardrail_rule SET scope = 'INPUT'
 WHERE rule_id = 'illegal' AND pattern <> 'PUA教学';

-- ② harm_others（请求形态）→ 输入侧
UPDATE guardrail_rule SET scope = 'INPUT'
 WHERE rule_id = 'harm_others';

-- ③ 输出侧专用：**建议类词 + 危险词，同句相邻（≤16 字）**
--    与 self_harm_incite 完全同形 —— 三处用同一套写法，便于将来统一演进。
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, scope) VALUES
('illegal_incite', 3, 'REGEX',
 '(?:你可以?|建议你?|教你|我来教你|方法|步骤|诀窍|怎样|如何|试试)[^。！？\\n]{0,16}(?:操控|拿捏|精神控制|打压|PUA)',
 1, '输出侧-教唆操控（仅 OUTPUT）', 'OUTPUT'),
('harm_others_incite', 3, 'REGEX',
 '(?:你可以?|建议你?|教你|我来教你|方法|步骤|诀窍|怎样|如何|试试)[^。！？\\n]{0,16}(?:杀|伤害|报复|打)(?:他|她|人)',
 1, '输出侧-教唆伤害他人（仅 OUTPUT）', 'OUTPUT');

-- 自检（期望：INPUT-only=21, OUTPUT-only=3, BOTH=22, 总 46）
--   SELECT IF(scope='OUTPUT','OUTPUT-only',IF(scope='INPUT','INPUT-only','BOTH')) s, COUNT(*)
--     FROM guardrail_rule WHERE enabled=1 GROUP BY s;
--
-- 回滚（见 docs/phase23-guardrail-scope-rollout/spec.md §5）
--   DELETE FROM guardrail_rule WHERE rule_id IN ('illegal_incite','harm_others_incite');
--   UPDATE guardrail_rule SET scope='BOTH' WHERE rule_id IN ('illegal','harm_others');
