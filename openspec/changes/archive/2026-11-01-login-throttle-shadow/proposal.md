# 登录节流增加 shadow 模式

## Why

`security-hardening` 上线了登录失败节流，阈值（5 次 / 15 分钟、IP 20 次）是**拍的**：
没有真实流量，不知道会误伤多少正常用户。硬阈值直接 enforce，一旦误伤就是"合法用户登不进来"，
这是最贵的一类错误。

本仓对护栏已有先例（ADR-36）：`off / shadow / enforce` 三态，shadow 只判定与落库、
绝不改变响应，先量真实误报率再决定要不要拦。登录节流应当采用同一套纪律。

## What Changes

- `app.auth.login-throttle.mode`：`off` / `shadow` / `enforce`，默认 **enforce**（不改变现网行为）。
- `shadow` 下：照常计数与写退避键、打日志、记指标 `auth.throttle.shadow_block`，
  但**永不抛 429**。
- 新增 `scripts/shadow_throttle_report.py`：从应用日志与 Prometheus 读 shadow 判定，
  按账号/IP 汇总"本来会被拦"的次数，给出阈值建议。
- `LoginThrottle.check` 的返回值从 void 改为可选判定结果（供 shadow 记录；enforce 忽略）。

## Capabilities

**Modified**：`safety-guardrails` 的「认证失败节流」——增加"可观测而不拦截"的运行形态，
明确 shadow 是**上线前的标定手段**，不是长期运行态。

## Impact

- 代码：`tenant/LoginThrottle`、`tenant/LoginThrottleProperties`
- 测试：`LoginThrottleTest` 增 shadow 三例
- 运维：默认 enforce，切 shadow 只改环境变量
