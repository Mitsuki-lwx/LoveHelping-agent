# phase23 · spec —— 只改数据，不改代码

> 判据 J1~J6 见 `tasks.md` §4。**本轮是纯数据迁移**：V25 已把 `scope` 维度做进判定逻辑，
> `GuardrailRuleService` 无需改动。

## 1. 迁移 V26

```sql
-- 裸词 / 输入侧请求形态 → INPUT（输出侧不再拦）
-- ⚠️ `PUA教学` **排除在外**：它是请求用语（只有用户会说"给我 PUA 教学"），
--    助手输出里几乎不会自然出现 → 误伤风险低，且它本身就是"教唆"语义 → 保留 BOTH。
UPDATE guardrail_rule SET scope='INPUT'
 WHERE rule_id='illegal' AND pattern <> 'PUA教学';   -- 2 条：诈骗(裸词) / (教|教我).{0,6}(PUA|操控…)
UPDATE guardrail_rule SET scope='INPUT'
 WHERE rule_id='harm_others';                        -- 1 条：请求形态

-- 输出侧专用：建议类词 + 危险词，**同句相邻**（与 self_harm_incite 同形）
INSERT INTO guardrail_rule (rule_id, level, pattern_type, pattern, enabled, description, scope) VALUES
('illegal_incite', 3, 'REGEX',
 '(?:你可以?|建议你?|教你|我来教你|方法|步骤|诀窍|怎样|如何|试试)[^。！？\\n]{0,16}(?:操控|拿捏|精神控制|打压|PUA)',
 1, '输出侧-教唆操控（仅 OUTPUT）', 'OUTPUT'),
('harm_others_incite', 3, 'REGEX',
 '(?:你可以?|建议你?|教你|我来教你|方法|步骤|诀窍|怎样|如何|试试)[^。！？\\n]{0,16}(?:杀|伤害|报复|打)(?:他|她|人)',
 1, '输出侧-教唆伤害他人（仅 OUTPUT）', 'OUTPUT');
```

## 2. 为什么条件写成 `rule_id=... AND pattern <> 'PUA教学'`

- `illegal` 在 V4 里有 **3 条**，逐条写 pattern 容易漏（本仓已有同类教训）→ 用 `rule_id` 圈定
- 但 `PUA教学` 要**单独排除**（请求用语 → 保留 BOTH，见 V26 注释）→ 用 `pattern <>` 排除，
  而不是把它写成第三条 `UPDATE`（少一条就少一个漏改点）
- ⚠️ 这条与 `tasks.md` §2 表格里"`PUA教学` 也降 INPUT"的初版判断**不同** ——
  写迁移时复核后改了，**表格已同步更正**

## 3. 正则设计取舍

- 与 `self_harm_incite` 完全同形 → **三处规则用同一套写法**，便于将来统一演进
- `[^。！？\n]{0,16}` 限定**同句**，避免"…如何识别诈骗？…（下一句）教他操控"这类跨句误配
- ⚠️ 已知漏判形态（放宽输出侧的代价）：不含建议类词的教唆，如
  "很多人最后都选了那条路"（`self_harm` 侧同理）

## 4. 预期 scope 分布变化（J6 的对照）

| | V25 后 | V26 后 | 变化 |
| --- | --- | --- | --- |
| INPUT-only | 18 | **21** | +3（`诈骗` + `illegal_teach` + `harm_others`） |
| OUTPUT-only | 1 | **3** | +2（`illegal_incite` + `harm_others_incite`） |
| BOTH | 25 | **22** | −3 |
| 总数 | 44 | **46** | +2（新增两条） |
| 校验 | | | 21+3+22 = 46 ✅ |

## 5. 回滚

```sql
-- 回滚 V26（V25 保留，因为它是本轮的依赖）
DELETE FROM guardrail_rule WHERE rule_id IN ('illegal_incite','harm_others_incite');
UPDATE guardrail_rule SET scope='BOTH' WHERE rule_id IN ('illegal','harm_others');
```
⚠️ V26 是**破坏性数据变更**（改了既有规则语义），回滚脚本已写入本文档（ADR-55 的 I7 缺这条，本轮补上）。

## 6. 验证

| 判据 | 装置 |
| --- | --- |
| J1 | `bash scripts/run_l3_trigger.sh`（「怎么识别只图钱的人」→ 应**不再被拦**） |
| J2 | 离线：`guardrail_offline_eval.py` 的教唆样本（`教你PUA她` / `你可以这样操控他` 应命中） |
| J3 | `bash logs/run_phase22_e2e.sh` 的断言 A5（输入侧） + 新增输入侧用例 |
| J4 | `python scripts/guardrail_offline_eval.py`（误报↓、召回不降） |
| J5 | `bash logs/mb.sh test` + `logs/run_phase22_e2e.sh` |
| J6 | 启动日志 `Guardrail rules loaded: 46 enabled (INPUT-only=22, OUTPUT-only=3, BOTH=21)` |
