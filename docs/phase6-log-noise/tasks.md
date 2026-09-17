# 任务：上游故障期日志降噪（Phase 6 收尾）

## 1. 目标

上游（DashScope 等）不可达时，应用日志**以可控速率增长**，且**不丢失可观测性**。

量化目标（基于 2026-09-16 长压实测）：

| 指标 | 现状 | 目标 |
| --- | --- | --- |
| 日志增速（上游全故障） | **≈3.6 GB/h** | **<0.2 GB/h**（降幅 ≥95%） |
| 30 分钟产出 | **1.78 GB** | **<100 MB** |
| 堆栈行占比 | **93.9%** | 显著下降（仅限流放行的那几条带堆栈） |
| 故障是否仍可见 | 可见但被淹没 | **仍可见**（限流样本 + 抑制计数 + 指标兜底） |

## 2. 事实基础（实测，非推断）

分析对象：`logs/soak/app-20260916c.log`（1.91 GB，16,008,138 行，堆栈行 15,031,336 = **93.9%**）。

| logger | 级别 | 次数 | 打全栈 | 同一事实我们是否已有等价可观测 |
| --- | --- | --- | --- | --- |
| `o.s.ai.chat.model.MessageAggregator` | ERROR | 104,157 | 是 | ✅ 同一异常已转 SSE `error` 事件 + `llm.call{outcome=fail}` |
| `o.s.a.r.a.SpringAiRetryAutoConfiguration` | WARN | 53,883 | 是 | ✅ 网关记 `llm.retry` / `llm.call`（SDK 重试已关为 1 次） |
| `c.a.c.a.d.e.DashScopeEmbeddingModel` | ERROR | 53,561 | 是 | ✅ `DegradingDocumentRetriever` 降级日志 + 检索降级指标 |
| `c.l.l.memory.MemoryVectorStore` | WARN | 19,759 | **否** | 自身即信息源（已只打 message） |
| `c.l.lwxaiagent.evolution.SkillRetriever` | INFO/WARN | 各 19,689 | **否** | 自身即信息源 |
| `c.l.l.rag.DegradingDocumentRetriever` | WARN | 14,030 | **否** | 自身即信息源 |

**结论**：体积来自**第三方 logger 每次失败打全栈**；我们自己的降级日志已经不打全栈（它们是要保留的）。

## 3. 做什么 / 不做什么

### 做
1. 新增 `src/main/resources/logback-spring.xml`（此前**没有** logback 配置文件，只有 `application.yml` 的 `logging.level`）。
2. 新增 `LogThrottleFilter`（logback `TurboFilter`）：按 **logger 名白名单 + 时间窗**限流，
   抑制期间计数、窗口结束打一条**汇总**（含被抑制条数）。
3. 白名单只含上表前三个第三方 logger；参数可配（`app.logging.throttle.*`），可整体关闭。
4. 补 ADR（可观测性策略变更：允许对"已知重复的第三方噪音"限流）。
5. 更新 `docs/09` §8.9(7) 的"未修复"为已修复 + 补实测对比。

### 不做
- **不改业务逻辑**（不碰 ChatExecutor / LlmGateway / 检索链路）。
- **不动我们自己的降级日志**（`MemoryVectorStore` / `SkillRetriever` / `DegradingDocumentRetriever`
  / `SkillReflector` / `GlobalExceptionHandler`）——它们是结构化信息源且已无堆栈。
- 不引入新依赖（logback 已随 Spring Boot 自带）。
- 不做日志轮转/归档策略（`logging.file.*`）——**本次只治"产生速率"**，轮转是另一个话题。
- 不删历史日志文件（`logs/` 在 gitignore，属用户数据，需单独确认后再清）。

## 4. 风险与对冲

| 风险 | 对冲 |
| --- | --- |
| 限流把真故障也压掉，排障时看不到 | ① 窗口内**仍放行 N 条**（含完整堆栈）；② 窗口结束打**汇总条**（"suppressed N similar"）；③ 只作用于白名单 logger，其他日志不受影响；④ 可配 `enabled=false` 一键回退 |
| TurboFilter 影响全局性能 | 只在 `decide()` 里做一次 map 查找 + 计数，无 IO；白名单外直接 `NEUTRAL` 放行 |
| 改可观测行为未经 ADR | 补 ADR-33，并在 `application.yml` 注释里写明 |
| 限流参数不当把正常期日志也压掉 | 正常期这些 logger **本来就不产生**日志（只有上游故障时才有），限流不触碰正常日志 |

## 5. 验收

见 `checklist.md`。核心：**真实复现**（DashScope 当前恰好不可达，可直接制造故障）测日志增速，
并与 `logs/soak/app-20260916c.log` 的 3.6 GB/h 基线对比。

## 6. 复现命令

```bash
# 基线（已测）：30 分钟长压产出 1.78 GB
#   logs/soak/app-20260916c.log
# 修复后：同样条件跑 N 分钟，比较文件增速
python scripts/soak.py --base http://127.0.0.1:<port>/api --minutes 5 --workers 16 --steady 16
```
