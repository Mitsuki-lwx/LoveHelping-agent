# 任务

## 1. 收口基础设施

- [x] 1.1 `Ownership` 改为无状态静态工具（`require` 抛 403 / `requireOrNull` 返回 null / 未登录抛 401）
- [x] 1.2 `ActionItemService` 回退为静态调用，去掉构造注入

## 2. 迁移 10 处手写比对（文案与状态码逐字不变）

- [x] 2.1 `controller/MediaController`（图片访问 403）
- [x] 2.2 `service/InsightService` ×2（图片 403、记录 403）
- [x] 2.3 `service/SandboxService` ×3（会话 403、记忆改 403、记忆删 403）
- [x] 2.4 `memory/MemoryStore` ×2（消息归属 403）
- [x] 2.5 `infrastructure/orchestration/graph/node/GraphVisionNode`（图片 403）
- [x] 2.6 `infrastructure/orchestration/graph/node/SandboxChatNode`（图片 403）
- [x] 2.7 `AiController` Agent 任务查询：**故意保留原样** —— 它带 ADMIN 旁路，语义不是纯归属，迁移会把旁路藏进 helper 里

## 3. 验证

- [x] 3.1 `mvn test` 全绿（656/656）
- [x] 3.2 E2E 27/27 全绿（含跨用户读/删/抢注负向用例）
- [x] 3.3 `OwnershipInventoryTest` 通过；信号表已把 `ownership.require` 换成 `Ownership.` 以匹配静态调用
- [x] 3.4 归档本 change
