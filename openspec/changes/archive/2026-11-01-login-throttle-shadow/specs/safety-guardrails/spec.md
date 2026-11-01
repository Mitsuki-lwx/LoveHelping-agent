## MODIFIED Requirements

### Requirement: 认证失败节流

系统 SHALL 对登录失败做节流：同一账号连续失败达到阈值后进入递增退避，并在窗口内锁定；
同一来源 IP 的失败尝试 SHALL 计入独立计数，防止换账号横向爆破。
节流 MUST 支持 `off / shadow / enforce` 三态；`shadow` 只判定与记录，MUST NOT 改变响应。

#### Scenario: 连续错误口令

- **WHEN** 同一账号在窗口内连续失败达到阈值（默认 5 次 / 15 分钟）
- **THEN** 后续登录返回 `code=429` 并提示稍后重试，且响应时间不透出"账号是否存在"

#### Scenario: 横向撞库

- **WHEN** 同一 IP 在窗口内对多个账号各失败 1 次
- **THEN** 达到 IP 阈值后统一退避，账号维度计数不受影响

#### Scenario: 锁定不影响已登录会话

- **WHEN** 某账号处于登录锁定中
- **THEN** 其已签发的 JWT 仍可正常访问（锁定只作用于认证动作）

#### Scenario: shadow 标定

- **WHEN** `mode=shadow` 且某账号本应被退避
- **THEN** 登录照常进行（不返回 429），但服务端记录一次 `would_block` 并累加指标
  `auth.throttle.shadow_block`，供上线前评估误伤

#### Scenario: 关闭节流

- **WHEN** `mode=off`
- **THEN** 不计数、不记录、不拦截；启动日志 MUST 打出 WARN
