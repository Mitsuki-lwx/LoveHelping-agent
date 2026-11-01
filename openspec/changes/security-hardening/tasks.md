# 任务

## 0. 准备

- [ ] 0.1 确认限流当前实际生效值（`app.rate-limit.daily-quota.enabled` 与 `burst-enabled`），记录到本 change
- [ ] 0.2 核对 Redis 是否在关键路径上（登录节流依赖它），确认不可用时的降级选择

## 1. 前端 scheme 白名单（零风险，先上）

- [x] 1.1 `front/src/views/LoveChat.vue` 的 `safeUrl` 拒绝协议相对 URL（`//host`）
- [x] 1.2 补用例：`//evil.example`、`javascript:`、`data:` 全部降级为纯文本；`https://` 与同源 `/api/...` 正常
- [x] 1.3 `npm test` 全绿（**278 项**，原 276 + 新增 2 条）

## 2. 归属校验收口

- [ ] 2.1 新增 `Ownership` 组件（加载 + 归属判定，404/403 语义分离）
- [ ] 2.2 把 `ActionItemService.owned`、`InsightService`、`MemoryService` 等手写比对改为走统一入口
- [ ] 2.3 新增 `OwnershipMatrixTest`：反射枚举 `{id}` 型端点，用"用户 A 的 token + 用户 B 的资源"断言 403
- [ ] 2.4 白名单机制：`@SkipOwnershipMatrix("理由")`，CI 打印白名单条数（防止无限增长）
- [ ] 2.5 跑 E2E 27 项 + 单测，确认无行为回归

## 3. 限流默认开启（行为变更，独立提交）

- [ ] 3.1 `app.rate-limit.daily-quota.enabled` 默认改 `true`
- [ ] 3.2 启动日志打印生效值与阈值；关闭时打 WARN
- [ ] 3.3 `.env.example` / `docs/11-环境与装置.md` 同步说明回滚方式
- [ ] 3.4 实测：配额耗尽返回 `B5001`，重启后恢复

## 4. 登录失败节流

- [ ] 4.1 新增 `LoginThrottle`（Redis 计数 + 指数退避），阈值可配 `app.auth.login-throttle.*`
- [ ] 4.2 `AuthController.login` 接入；失败/成功路径统一文案，不透出账号是否存在
- [ ] 4.3 Redis 不可用 → fail-open + WARN（不阻断登录），并在指标里记 `auth.throttle.degraded`
- [ ] 4.4 单测：5 次失败后 429；锁定期间已签发 JWT 仍可用；IP 维度横向撞库被拦
- [ ] 4.5 E2E 负向用例：暴力破解路径返回 429 且不影响正常登录

## 5. 收尾

- [ ] 5.1 更新 `openspec/specs/safety-guardrails/spec.md`（archive 时自动合并 delta）
- [ ] 5.2 全量门禁：`mvn test` 650+、`npm test` 276+、E2E 27 项、`openspec validate --strict`
- [ ] 5.3 归档本 change
