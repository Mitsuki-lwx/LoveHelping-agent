# 归属校验全量收口

## Why

`security-hardening` 已引入 `Ownership` 与盘点绊线，但只有 `ActionItemService` 真正迁移过去；
其余 10 处仍是各 service/节点自己写 `if (!userId.equals(x.getUserId()))`。盘点测试只保证
"方法体里有信号"，**保证不了比对写对了**——这正是要收口的原因。

## What Changes

- `Ownership` 改为**无状态静态工具**（不再需要注入）：`require(...)` 抛 403，
  `requireOrNull(...)` 返回 null，未登录抛 401。
- 把 10 处手写比对改为调用它，**异常文案与状态码逐字保持不变**。
- `ActionItemService` 回退为静态调用（去掉构造注入，避免为一个纯函数改构造签名）。
- `AiController` 的 Agent 任务查询**暂不迁移**：它带 ADMIN 旁路，语义不是"纯归属"，
  迁移会让旁路逻辑更隐蔽；在 tasks 里登记为"保留原样 + 原因"。

## Capabilities

纯重构，**不改变任何外部可观察行为** → `skip_specs: true`。
归属的行为约束已由 `specs/safety-guardrails` 的「归属校验收口」覆盖。

## Impact

- 代码：`tenant/Ownership`、`service/ActionItemService`、`service/SandboxService`、
  `service/InsightService`、`memory/MemoryStore`、`controller/MediaController`、
  `infrastructure/orchestration/graph/node/{GraphVisionNode,SandboxChatNode}`
- 行为：无变化（文案/状态码逐字保持）
- 测试：单测与 E2E 必须全绿
