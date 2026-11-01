## ADDED Requirements

### Requirement: 认证失败节流

系统 SHALL 对登录失败做节流：同一账号连续失败达到阈值后进入递增退避，并在窗口内锁定；
同一来源 IP 的失败尝试 SHALL 计入独立计数，防止换账号横向爆破。

#### Scenario: 连续错误口令

- **WHEN** 同一账号在窗口内连续失败达到阈值（默认 5 次 / 15 分钟）
- **THEN** 后续登录返回 `code=429` 并提示稍后重试，且响应时间不透出"账号是否存在"

#### Scenario: 横向撞库

- **WHEN** 同一 IP 在窗口内对多个账号各失败 1 次
- **THEN** 达到 IP 阈值后统一退避，账号维度计数不受影响

#### Scenario: 锁定不影响已登录会话

- **WHEN** 某账号处于登录锁定中
- **THEN** 其已签发的 JWT 仍可正常访问（锁定只作用于认证动作）

### Requirement: 限流默认开启

系统 SHALL 默认启用请求限流，并 MUST 在启动日志中打印实际生效的限流开关与阈值。
关闭限流 MUST 是显式配置行为，且 MUST 在启动日志留下告警。

#### Scenario: 默认配置启动

- **WHEN** 未设置 `DAILY_QUOTA_ENABLED`
- **THEN** 限流生效，启动日志打印 `rate-limit: enabled=true`

#### Scenario: 显式关闭

- **WHEN** 设置 `DAILY_QUOTA_ENABLED=false`
- **THEN** 限流关闭，启动日志打印 `WARN rate-limit disabled by config`

### Requirement: 归属校验收口

按 id 读取用户资源的路径 SHALL 经过统一入口校验归属；跨用户访问 MUST 返回 `403` 或 `B2002`，
且 MUST 有矩阵测试覆盖所有 `{id}` 型端点。

#### Scenario: 新增端点漏写归属校验

- **WHEN** 新增一个按 id 取用户资源的端点却未走统一校验
- **THEN** 矩阵测试失败并指出该端点，阻止合入

#### Scenario: 合法归属

- **WHEN** 用户以自己的 id 访问自己的资源
- **THEN** 正常返回，不因收口引入额外失败

### Requirement: 前端链接 scheme 白名单

前端 SHALL 只允许 `http:`、`https:` 与同源绝对路径作为可点击链接与图片地址；
协议相对 URL（`//host`）与其他 scheme MUST 被拒绝并降级为纯文本。

#### Scenario: 协议相对 URL

- **WHEN** 模型输出包含 `[点我](//evil.example/path)`
- **THEN** 渲染为纯文本，不生成可点击链接

#### Scenario: javascript scheme

- **WHEN** 模型输出包含 `[x](javascript:alert(1))`
- **THEN** 降级为纯文本，不产生 `href`

## MODIFIED Requirements

### Requirement: 用户级数据隔离

按 conversationId / messageId / mediaId 取数的每条路径 SHALL 校验归属；
跨用户访问 MUST 返回 `B2002`，且 MUST NOT 返回任何他人数据。
校验 MUST 通过统一入口完成，MUST NOT 依赖各服务各自手写比对。

#### Scenario: 读取他人会话

- **WHEN** 用户 A 用用户 B 的 `conversationId` 请求详情、消息、计数或删除
- **THEN** 全部返回 `B2002`

#### Scenario: 抢注会话

- **WHEN** 用户 A 对用户 B 已有的会话标识调用 `POST /memory/register`
- **THEN** 返回 403，不覆盖归属

#### Scenario: 新增端点漏写归属校验

- **WHEN** 新增一个带 `{id}` 的端点，其处理函数体内没有任何归属校验信号
- **THEN** `OwnershipInventoryTest` 失败并指出「文件:行」，不依赖任何人记得去登记

#### Scenario: 越权请求的行为回归

- **WHEN** 运行真实栈 E2E 的跨用户负向用例（读/删/抢注）
- **THEN** 全部返回 403 或 `B2002`；该行为断言由 E2E 承担，盘点测试只负责"不漏写"
