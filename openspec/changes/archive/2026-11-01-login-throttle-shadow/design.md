# 设计：shadow 不是"另一个开关"，是上线前的量具

## 三态语义

| mode | 计数 | 写退避键 | 拦截 | 用途 |
|---|---|---|---|---|
| `off` | ✗ | ✗ | ✗ | 排障/压测时临时关闭 |
| `shadow` | ✓ | ✓ | ✗ | **上线前标定**：量出"本来会被拦"的人是谁、有多少 |
| `enforce` | ✓ | ✓ | ✓ | 默认；真实运行态 |

默认 `enforce` 是刻意的：本变更**不改变现网行为**，只是把标定手段补齐。

## 判定与决策分离

现有 `check()` 在命中退避时直接抛 429，判定与决策耦合在一起。改成：

```java
Verdict v = throttle.evaluate(username, ip);   // 纯判定：LOCKED / ALLOWED
if (v.locked() && mode == ENFORCE) throw v.toException();
if (v.locked()) shadowRecord(v);               // shadow：只记不收
```

这样 shadow 与 enforce 走**同一份判定逻辑**——否则量出来的不是线上那套规则（"影子跑的不是产线代码"是这类方案最经典的失败模式）。

## 报告脚本

`scripts/shadow_throttle_report.py` 读两处：

1. 应用日志里的 `[login-throttle] would_block` 行（含账号哈希、IP、触发维度、当前失败数）；
2. Prometheus `/actuator/prometheus` 的 `auth_throttle_shadow_block_total`。

输出：按维度分组的 would_block 次数、受影响账号数、按小时的分布，并给出"若按当前阈值 enforce，会拦掉 N 个账号 / M 次登录"的换算。

## 代价

- 多一次 Redis 读（shadow 与 enforce 相同，可忽略）。
- shadow 期间退避键照写：若之后切 enforce，**影子期攒下的失败会计入真实锁定**。
  这是有意的（同一份状态），但切换时要在日志里说明，避免"上线瞬间锁一片"。
- 阈值标定需要真实流量；没有流量时本模式只能验证"不误伤"这一半。

## 不做

- 不做按用户画像自适应阈值（没有数据支撑，属过度设计）。
- 不做多实例聚合视图（单实例 Redis 计数天然全局）。
