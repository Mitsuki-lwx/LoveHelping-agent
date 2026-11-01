# 安全加固：认证节流、归属校验收口与 XSS 残留面

## Why

2026-11-01 的一次聚焦安全审计（源级检查，非渗透）发现三类**可被利用的缺口**，且它们都落在
「已经声称做过安全治理」的面上：

1. **认证无失败节流**：`/auth/login` 没有失败计数、锁定或退避；而全站限流默认关闭
   （`app.rate-limit.daily-quota.enabled=${DAILY_QUOTA_ENABLED:false}`），等于口令爆破零成本。
2. **归属校验靠逐点手写**：IDOR 已在多条路径修过（`owned()`、`media.getUserId()` 等），
   但**没有统一收口**——新增一个按 id 取数的端点时，漏写一次就重新开一个洞，且 CI 抓不到。
3. **XSS 修复后仍有残留面**：ADR-79 修掉的是「URL 拼进属性未转义」；当前 `renderMarkdown` 走
   `v-html`，`safeUrl` 放行以 `/` 开头的任意串，因此 `//evil.example` 这种**协议相对 URL**
   会被渲染成外链（钓鱼/开放跳转面），且没有任何回归用例覆盖。

本变更把这三类问题变成**可判定的需求 + 可失败的测试**，而不是再写一段文档说明。

## What Changes

- **新增**：登录失败节流与渐进锁定（按 账号 + 来源 IP），阈值可配置；锁定不影响已登录会话。
- **新增**：限流从「默认关」改为「默认开、按环境可关」，并在启动日志打印实际生效开关（避免"以为开了"）。
- **新增**：归属校验收口——所有按 id 取用户资源的路径必须经过同一入口（`Ownership.owned(userId, resource)`），
  并补一条**矩阵测试**：遍历控制器里所有 `{id}` 型端点，断言跨用户访问返回 403/B2002。
- **修改**：前端 `safeUrl` 增加 scheme 白名单（仅 `http:`/`https:`/同源绝对路径），拒绝 `//host`。
- **新增**：安全回归用例集（暴力破解、越权矩阵、XSS 载荷、上传路径穿越）进 CI。
- **BREAKING（运维）**：限流默认开启后，未配置配额的环境会开始返回 `B5001`；`docs`/`.env.example` 同步说明。

## Capabilities

**Modified Capabilities**：

- `safety-guardrails` — 新增「认证失败节流」「限流默认开启」「归属校验收口」「链接 scheme 白名单」四条
  requirement；修改「用户级数据隔离」的验证方式（从逐点人工审计改为矩阵测试）。

## Impact

- 代码：`tenant/UserService`、`controller/AuthController`、`service/RateLimiter`、
  `tenant/interceptor/TenantInterceptor`、`front/src/views/LoveChat.vue`
- 配置：`app.rate-limit.*`、新增 `app.auth.login-throttle.*`
- 测试：新增 `OwnershipMatrixTest`、`LoginThrottleTest`、`safeUrl` 前端用例
- 运维：默认开启限流会改变现网行为，需要灰度与回滚开关
- 数据：无 schema 变更（节流状态放 Redis）
