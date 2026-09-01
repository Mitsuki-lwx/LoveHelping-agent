# spec.md — 后台调度器节流与错峰（Scheduler Backpressure）

> **状态**：待评审（实现前先定稿）
> **触发依据**：2026-09-01 Langfuse 全链路测评实测——20 分钟内后台调度产生 **89 次 embedding + 6 次 chat LLM 调用**，同期真实用户请求仅 1 次；
> 单轮 `memory-extraction-scheduler.scan-and-extract` p50=39.5s、max=118.7s。
> **前置**：ADR-20（需在 `docs/03-技术决策记录.md` 立："后台任务必须受节流预算约束，且不得与在线请求争抢 LLM 配额"）。

---

## 1. 问题

| 现象 | 证据 | 后果 |
|------|------|------|
| 后台调度占绝大比例 LLM 调用 | 20 分钟内 95 次后台调用 vs 1 次用户请求 | 成本几乎全部花在后台 |
| 淹没有效监控数据 | 查询请求 trace 时反复被调度 trace 挤出 100 条窗口 | 全链路可观测性被自己拖垮 |
| 抢在线请求配额 | 在线 chat 429（智谱 code 1302）与调度 LLM 峰重叠 | 用户侧延迟 p99 达 82s |
| 单轮任务过长 | p50 39.5s / max 118.7s | 长时间占用线程池与连接 |
| 存量数据持续复扫 | 历史会话反复进入候选集 | 空转调用 |

**根因**：三个调度器的节奏参数（5 分钟 / 30 分钟）与批量上限（10 / 20）是**写代码时拍的常量**，与"每分钟允许消耗多少 LLM 配额"这个真实约束没有任何关联；且调度器之间彼此独立，没有共享预算。

## 2. 目标与非目标

**目标**
1. 后台 LLM 调用量可预算、可配置、可关停（不改代码即可降级到 0）
2. 在线请求优先：后台任务不与在线请求争抢同一 LLM 配额预算
3. 空转自动退避：没有候选时不产生调用，且频率随时间衰减
4. 每轮的消耗（候选数 / 处理数 / 调用次数 / 耗时）在 Langfuse 与指标里可见

**非目标（Out of Scope）**
- 不重构调度器的业务逻辑（萃取什么、反思什么都保持不变）
- 不引入 MQ / 分布式调度（单机单机部署，保持简单）
- 不改变记忆/技能的数据结构（不新增表、不改 Flyway）
- 不处理"技能审核积压"（262 条 PENDING）这一独立议题

## 3. 能力清单（CAP）

| 编号 | 能力 | 说明 |
|---|---|---|
| CAP-1 | 统一节流配置 | `app.scheduler.*`：各调度器开关、扫描间隔、单轮批量上限 |
| CAP-2 | 单轮预算 | 每轮最多 N 次 LLM 调用、最长 T 毫秒，超出则本轮结束、剩余下轮 |
| CAP-3 | 共享配额闸门 | 后台 LLM 调用统一经一个令牌桶，与在线 `RateLimiter` 同一预算体系但独立配额 |
| CAP-4 | 空转退避 | 连续 K 轮无候选 → 间隔指数退避（上限封顶），有候选立即恢复 |
| CAP-5 | 错峰窗口 | 可配置"低峰时段"（默认全天允许，可设为仅夜间），非窗口内跳过 |
| CAP-6 | 可观测 | 每轮输出候选数/处理数/LLM 调用数/耗时；Langfuse trace 标记 `background=true` |
| CAP-7 | 一键静默 | `app.scheduler.master-enabled=false` 关闭全部后台调度（排查与压测用） |

## 4. 设计骨架

```
@Scheduled(固定节奏，仅做"触发器")
      │
      ▼
SchedulerBudget（共享组件，单例）
      │ ① 主开关 master-enabled
      │ ② 本调度器 enabled
      │ ③ 错峰窗口
      │ ④ 令牌桶取配额（app.scheduler.llm-budget-per-minute）
      │ ⑤ 空转退避判定
      ▼
本轮预算 = min(批量上限, 剩余配额, 时间预算)
      │
      ▼
逐个处理：每次 LLM 调用前扣 1 个配额；配额或时间耗尽即停
      │
      ▼
收尾：记录指标 + Langfuse trace（background=true，sessionId=调度器名）
```

**关键约束**
- 节流逻辑集中在 `SchedulerBudget` 组件，三个调度器只"问一句能不能做、能做几个"，各自业务不变
- 所有阈值可配置、有默认值，默认行为必须对现有功能**无回归**（默认批量上限与现一致，默认配额充足时行为等价）
- 调度器异常不得影响在线请求：预算组件自身异常时**默认放行**（fail-open），并告警
- 令牌桶是进程内的（单机部署足够，ADR-13 多租户冻结，不做跨实例协调）

## 5. 涉及的调度器现状

| 调度器 | 当前节奏 | 当前批量 | 每轮 LLM/embedding 调用 |
|---|---|---|---|
| `ReflectionScheduler.scanAndReflect` | fixedDelay 5min（硬编码） | LIMIT 20（硬编码） | 每会话 1 次 LLM（反思） |
| `MemoryExtractionScheduler.scanAndExtract` | fixedDelay 30min（硬编码） | BATCH_LIMIT 10（硬编码常量） | 每会话 1 次 LLM（萃取）+ 1 次 embedding |
| `MemoryExtractionScheduler.runLifecycle` | fixedDelay 24h | — | 无 LLM（纯 SQL 清理） |
| `AgentTaskScheduler.compensate-stale` | 见其自身配置 | — | 无 LLM |

## 6. 验收口径

见 `checklist.md`：每一项必须是可执行的 curl / SQL / grep / Langfuse 查询，**不许写"功能完整"这类不可观测描述**。
