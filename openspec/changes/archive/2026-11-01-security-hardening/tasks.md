# 任务

## 0. 准备

- [ ] 0.1 确认限流当前实际生效值（`app.rate-limit.daily-quota.enabled` 与 `burst-enabled`），记录到本 change
- [ ] 0.2 核对 Redis 是否在关键路径上（登录节流依赖它），确认不可用时的降级选择

## 1. 前端 scheme 白名单（零风险，先上）

- [x] 1.1 `front/src/views/LoveChat.vue` 的 `safeUrl` 拒绝协议相对 URL（`//host`）
- [x] 1.2 补用例：`//evil.example`、`javascript:`、`data:` 全部降级为纯文本；`https://` 与同源 `/api/...` 正常
- [x] 1.3 `npm test` 全绿（**278 项**，原 276 + 新增 2 条）

## 2. 归属校验收口

- [x] 2.1 新增 `Ownership` 组件（`require` 严格版 401/404/403；`requireOrNull` 宽松版含显式 userId 重载）
- [x] 2.2a 首批迁移：`ActionItemService.owned` 改走 `Ownership.requireOrNull(userId, …)`
- [ ] 2.2b 其余手写比对逐步收口（`InsightService`、`MemoryService` 等）；本变更不强制一次改完
- [x] 2.3 新增 `OwnershipInventoryTest`（方案 A）：扫描 26 个 `{*Id}` 端点，要求方法体内有归属校验信号
- [x] 2.4 反向验证：植入一个无守卫端点 → 测试变红并打印「文件:行」；删除 → 变绿（证明不是恒真判据）
- [x] 2.5 跑 E2E 27 项 + 单测：**E2E 27/27、单测 651/651**（2026-11-01 实测，限流默认开启下跑）

## 3. 限流默认开启（行为变更，独立提交）

- [x] 3.1 `app.rate-limit.enabled` 默认改 `true`，`daily-quota` 默认 5 → 100（5 是收费档假设）
- [x] 3.2 启动日志打印生效值与阈值；关闭时打 WARN（实测两行日志，见验证）
- [x] 3.3 `.env.example` 同步回滚方式（`DAILY_QUOTA_ENABLED=false`）
- [x] 3.4 实测：quota=1 时第 2 次返回 `code=429 reason=daily`；回滚开关重启后同一用户恢复 200
- [x] 3.5 修正契约：`api-contract` delta —— `B1xxx/E5000` 从未实现（全仓 0 命中），改为实际数字码

## 4. 登录失败节流

- [x] 4.1 新增 `LoginThrottle`（Redis 账号/IP 双维度计数 + 指数退避），阈值可配 `app.auth.login-throttle.*`
- [x] 4.2 `AuthController.login` 接入；退避期内直接 429，响应文案不区分"账号不存在/密码错误"
- [x] 4.3 Redis 不可用 → fail-open + WARN，并记 `auth.throttle.degraded` 指标
- [x] 4.4 单测 5 条：退避期 429 带 retryAfterSec · 未锁定放行 · 阈值写退避键 · 成功清零 · Redis 故障 fail-open
- [x] 4.5 实测：5 次错误后第 6 次被拦；锁定期间**正确口令也返回 429**；另一账号正常登录；E2E 27/27 无回归

## 5. 收尾

- [x] 5.1 更新 `openspec/specs/safety-guardrails/spec.md`（archive 时自动合并 delta）
- [x] 5.2 全量门禁：`mvn test` **656**、`npm test` **278**、E2E **27/27**、`openspec validate --strict`
- [x] 5.3 归档本 change
