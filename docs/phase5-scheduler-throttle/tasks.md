# tasks.md — 后台调度器节流与错峰实现任务

> **前置**：ADR-20 已立（`docs/03-技术决策记录.md`）；依赖现有 `RateLimiter`、`MeterRegistry`、Langfuse 接入。
> 原则：每步可编译、可独立验证；默认配置对现有行为无回归（AGENTS.md 工程习惯）。

---

## Task 1：立 ADR-20（后台调度预算约束）

**目标**：把"后台任务不得与在线请求争抢配额"写成决策记录，避免本次实现被认为是随手调参。
**依赖**：无
**影响文件**：`docs/03-技术决策记录.md`
**内容**：记录背景（2026-09-01 实测数据）、决策（共享预算 + 空转退避 + 错峰）、被否方案（引入 MQ / 直接关停进化模块）、后果（后台沉淀速度变慢，可通过配额调大补偿）

## Task 2：新增 `SchedulerBudget` 组件 + 配置属性

**目标**：节流能力集中在一处，三个调度器只做"问询"。
**依赖**：Task 1
**影响文件**：
- 新增 `infrastructure/scheduler/SchedulerBudget.java`
- 新增 `infrastructure/scheduler/SchedulerProperties.java`（`@ConfigurationProperties(prefix = "app.scheduler")`）
- `application.yml`：新增 `app.scheduler` 配置段（含注释说明每项含义与默认值）

**内容**：
- 属性：`master-enabled`、`reflect.{enabled,fixed-delay-ms,batch-limit}`、`extract.{enabled,fixed-delay-ms,batch-limit}`、`llm-budget-per-minute`、`max-run-ms`、`idle-backoff.{threshold,max-multiplier}`、`quiet-hours.{start,end}`
- API：`boolean permitted(String schedulerName)`、`int allowance(String name, int want)`、`void consume(String name, int n)`、`void recordOutcome(String name, int candidates)`
- 令牌桶：按分钟配额匀速补充；`max-run-ms` 由调用方用 `System.currentTimeMillis()` 自行判定（预算组件只提供起始时间与上限）

## Task 3：`ReflectionScheduler` 接入预算

**目标**：把 5 分钟/20 会话的硬编码改成可配置 + 受预算约束。
**依赖**：Task 2
**影响文件**：
- `evolution/ReflectionScheduler.java`（节奏改为读配置、批量取 `allowance`、每次反思前 `consume`）
- 新增 `infrastructure/scheduler/SchedulerBudgetTest`（配额/退避/错峰的纯单测）

**内容**：
- 移除 `@Scheduled` 硬编码节奏，改为 `fixedDelayString = "${app.scheduler.reflect.fixed-delay-ms}"`（保留 initialDelay）
- `LIMIT 20` → `LIMIT :batchLimit`（`batchLimit = budget.allowance("reflect", 20)`）
- 每个会话反思前 `consume("reflect", 1)`；配额耗尽或超过 `max-run-ms` 即跳出循环并记录日志

## Task 4：`MemoryExtractionScheduler` 接入预算

**目标**：同 Task 3，覆盖萃取链路（LLM + embedding 双重调用）。
**依赖**：Task 2
**影响文件**：`memory/MemoryExtractionScheduler.java`、`application.yml`

**内容**：
- `BATCH_LIMIT` 常量 → 配置 + `budget.allowance("extract", n)`
- 萃取（LLM）与写记忆（embedding）各计 1 次配额消耗
- 无候选 / 无归属跳过时**不计消耗**
- `runLifecycle` 保持 24h 不变（无 LLM 调用，不受预算约束）

## Task 5：空转退避

**目标**：连续空转时降低频率，避免"每 5 分钟唤醒一次只为发现没有候选"。
**依赖**：Task 3、Task 4
**影响文件**：`infrastructure/scheduler/SchedulerBudget.java`、两个调度器（调用点）

**内容**：
- 连续 `idle-backoff.threshold` 轮候选为 0 → 实际间隔 ×2（封顶 `max-multiplier`，默认 8 倍）
- 出现候选立即复位
- 退避倍数写进日志，便于容量复盘

## Task 6：可观测（指标 + Langfuse 标记）

**目标**：后台消耗可量化，且能在 Langfuse 里与在线请求区分开。
**依赖**：Task 3、Task 4
**影响文件**：`SchedulerBudget.java`、两个调度器、`GraphObservability` 无改动

**内容**：
- 指标：`scheduler.round{name,outcome}`、`scheduler.candidates{name}`、`scheduler.processed{name}`、`scheduler.llm_calls{name}`、`scheduler.skipped{name,reason}`
- Langfuse：调度轮次 trace 打 `background=true` 标记，便于过滤掉后台噪声（当前后台 trace 占 99%，严重淹没在线数据）
- 每轮一条 INFO 日志：`SCHED_ROUND name= candidates= processed= llmCalls= elapsedMs= skippedReason=`

## Task 7：E2E 验证与回归

**目标**：证明节流生效且在线链路无回归。
**依赖**：Task 2–6
**影响文件**：`scripts/e2e-smoke.sh`（可选新增 §7.11 后台节流观测段）

**内容**：
- 编译 + 全量单测（含新增 `SchedulerBudgetTest`）
- 启动应用静置 15 分钟，用 Langfuse API 统计该窗口内 `background` 与在线 trace 数量，与修改前基线对比
- 跑 `scripts/e2e-smoke.sh` 完整冒烟，确认 §7.1–7.10 无回归
