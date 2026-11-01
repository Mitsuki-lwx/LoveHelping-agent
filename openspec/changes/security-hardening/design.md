# 设计：怎么把这些缺口补成机制

## 1. 认证失败节流

**位置**：`UserService.authenticate` 之前，在 `AuthController.login` 或独立 `LoginThrottle` 组件。
放 Redis，键两组：

```
auth:fail:user:{username}   -> 计数（TTL 15m）
auth:fail:ip:{ip}           -> 计数（TTL 15m）
auth:lock:{username}        -> 1（TTL = 退避秒数）
```

**退避**：失败次数 n ≥ 5 时，锁定 `min(30 * 2^(n-5), 900)` 秒。

**代价**：
- 账号维度锁定可被用来**拒绝服务**（攻击者故意锁某人）。缓解：锁定只作用于该账号的来源 IP 组合，
  或退避而非硬锁；本变更采用**指数退避 + 不返回剩余秒数的具体原因**（统一文案），把 Dol 风险降到可接受。
- Redis 不可用时必须 **fail-open 但告警**（登录不能因缓存挂了全站不可用），这与「宁可严」有冲突，
  需在 design 里写明选择：可用性优先，告警兜底。

**响应时间**：失败路径与成功路径都要做最小耗时填充，避免用户枚举。

## 2. 限流默认开启

`app.rate-limit.daily-quota.enabled` 默认值 `false → true`，并在 `RateLimiter` 初始化时
`log.info` 打印生效值；`false` 时 `log.warn`。

**代价**：现网默认行为改变（用户可能在配额内被拦）。回滚 = 环境变量置 false，不需发版。

## 3. 归属校验收口

现状：每个 service 自己 `if (!userId.equals(x.getUserId())) throw`。问题不是写错，而是**漏写不可发现**。

方案：引入 `Ownership` 组件：

```java
public <T> T require(Supplier<T> loader, Function<T,String> ownerOf, String userId)
```

- 资源不存在 → 404 语义；存在但非本人 → 403。
- 强制所有按 id 取数的路径走它。

**盘点测试（方案 A，2026-11-01 决策）**：扫描控制器源码里所有带 `{*Id}` 的映射，要求处理函数体内
出现归属校验信号（`checkOwnership(` / `ownership.require` / `owned(` / `getUserId()` /
`requireUserId()` / `adminGuard` / `@RequireRole`）；缺失即失败并打印「文件:行」。

为什么不选运行时矩阵（26 个端点 × 每类资源造夹具 + 真起上下文，约 1–2 天）：
本仓测试分层是「Mockito 单测 + Testcontainers IT + 脚本级 E2E」，塞一个 MockMvc 矩阵会破坏分层，
而它要证明的**行为**已经由 E2E 的跨用户负向用例覆盖。方案 A 补的是**遗漏检测**，成本约 1/10。

**代价（写清楚）**：它是源码启发式，不是运行时断言——一个"调了 `getUserId()` 却没真比对"的端点能骗过它。
因此配套要求：信号必须出现在**处理函数体内**，且 `OwnershipInventoryTest` 已做过**反向验证**
（植入一个无守卫端点 → 变红；删除 → 变绿），确保它不是恒真判据。
同一 `{id}` 端点若把校验藏进 service，需在控制器里留下可识别信号（如 `requireUserId()`）。

## 4. 前端 scheme 白名单

```js
function safeUrl(raw) {
  const url = String(raw ?? '').trim()
  if (/^https?:\/\//i.test(url)) return url
  if (url.startsWith('/') && !url.startsWith('//')) return url   // 拒绝协议相对
  if (url.startsWith('#')) return url
  return ''
}
```

`v-html` 本身保留（渲染 markdown 需要），但**所有插值必须经过 `escAttr`**——这条已有，
本变更只补 `//` 与 scheme 用例，并把 `renderMarkdown` 的用例数从"图片 1 条"扩到链接/图片/协议三类。

## 5. 顺序与可回滚

1. 前端白名单（零风险，先上）
2. 归属收口 + 矩阵测试（只加约束，不改行为）
3. 限流默认开（行为变更，独立 commit，带回滚开关）
4. 登录节流（最后，依赖 Redis 健康）

每步单独 commit，任一步出问题可独立 revert。

## 未做（明确不承诺）

- 不引入 WAF/网关层防护（部署面不在本仓）
- 不做密码强度策略改造（属产品策略，另开 change）
- 不做安全扫描器接入（gitleaks 已有，SAST 另议）
