# 任务

## 1. 三态与判定分离

- [x] 1.1 `LoginThrottleProperties` 增加 `mode`（enum：OFF / SHADOW / ENFORCE，默认 ENFORCE）
- [x] 1.2 `LoginThrottle` 拆出纯判定 `evaluate(username, ip)` → `Verdict(locked, retryAfter, dimension)`；`check()` 保留为兼容入口
- [x] 1.3 `enforce` 下命中即抛 429（文案与 data 不变）；`shadow` 下记日志 + 指标 `auth.throttle.shadow_block`，**不抛**
- [x] 1.4 `off` 下不计数不记录；启动日志对 `off` / `shadow` 各打一条 WARN（说明当前不是强制态）

## 2. 观测

- [x] 2.1 shadow 日志行格式固定：`[login-throttle] would_block dimension=user|ip user_hash=… ip=… failures=N`
      （账号只打哈希，不落明文）
- [x] 2.2 新增 `scripts/shadow_throttle_report.py`：读日志 + Prometheus，输出 would_block 分布与阈值建议

## 3. 验证

- [x] 3.1 单测：`enforce` 抛 429 / `shadow` 不抛且计数 / `off` 完全不碰 Redis
- [x] 3.2 实测：shadow 下连错 8 次，正确口令**仍登录成功**；日志出现 4 条 would_block（30/60/120s 递增退避，账号只打哈希）
- [x] 3.3 实测：影子期攒下失败（中间未成功登录）后切 enforce，同一账号**立即 429 retryAfterSec=95**；另注：成功登录会清零，故须"无成功登录"才复现
- [x] 3.4 `mvn test` 全绿；归档本 change
