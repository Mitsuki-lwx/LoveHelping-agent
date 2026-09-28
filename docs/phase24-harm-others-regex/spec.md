# phase24 · spec —— 一条 `UPDATE`，零 Java 改动

## 1. 迁移 V27

```sql
-- 只加 `了?`：口语里"杀了他/害了他"极自然，旧 pattern 要求三词紧邻 → 漏判
UPDATE guardrail_rule
   SET pattern = '(?i)(想|要|计划)(杀|害|报复)了?(他|她|人)'
 WHERE rule_id = 'harm_others';
```

⚠️ **不新增规则、不改 scope、不改 Java** —— 只改一条既有规则的 pattern。

## 2. 为什么用 `UPDATE ... WHERE rule_id='harm_others'`

该 rule_id 在 V4 里**只有 1 条**（已核实），但用 `rule_id` 而非 `pattern` 匹配，
是为了**与 V25/V26 的写法保持一致**（避免将来加条目时漏改）。

## 3. 边界设计（见 tasks §4）

| 放宽方式 | 采纳？ | 理由 |
| --- | --- | --- |
| `了?` | ✅ | 覆盖口语最自然的形态，误报增量最小 |
| `[了掉死]?` | ✗ | "杀死了/害死了"也可能是叙述往事，误报增量未评估 |
| `.{0,4}` | ✗ | 会命中"我想了解他"之外的更多正常表达，误报不可控 |

## 4. 预期影响面

- **输入侧**：`harm_others` 是 `scope=INPUT`（V26 降的）→ 只影响**用户输入**的拦截
- **输出侧**：不涉及（`harm_others_incite` 是另一条规则）
- **scope 分布**：**不变**（`46 enabled (INPUT-only=21, OUTPUT-only=3, BOTH=22)`）
  → J6 的分布断言本轮**应当不变**，变的是 pattern 内容本身

## 5. 回滚

```sql
UPDATE guardrail_rule
   SET pattern = '(?i)(想|要|计划)(杀|害|报复)(他|她|人)'
 WHERE rule_id = 'harm_others';
```

## 6. 验证

| 判据 | 装置 |
| --- | --- |
| J1 / J2 / J3 | `python scripts/guardrail_offline_eval.py`（样本集本轮扩充：加"杀了"类正样本 + 判别负样本） |
| J5 | `bash logs/mb.sh test` + `bash logs/run_phase22_e2e.sh` |
| J6 | 启动日志 scope 分布不变 + **DB pattern 实查**（离线脚本已同步新式，两处一致才算生效） |
