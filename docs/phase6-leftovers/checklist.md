# Phase 6 遗留收敛 —— 验收清单（checklist）

> 逐条可独立判定。**未通过项显式写明原因。** 结果记录时间：2026-09-19。

## 0. 文档（前置门禁）

- [x] `tasks.md` 已写明做什么 / 不做什么 / 边界
- [x] `spec.md` 已给出方案、被否方案与理由
- [x] 本清单写完才开始写代码

## 1. T1 跨线程上下文守护

- [x] 新增 `ContextPropagationGuardTest`，扫描 `src/main/java` 中每处 `new ThreadPoolTaskExecutor()`
- [x] 每个池所在方法体必须出现 `setTaskDecorator(`，否则失败并**指名到文件与方法/行号**
- [x] 豁免以显式常量表登记（`graphExecutor`，理由与 `spec.md` §S1.3 一致）
- [x] 反恒真断言：扫到的池数量 < 2 时失败
- [x] **反向对照实验**：摘掉 `EvolutionConfig` 的 `setTaskDecorator` → 失败报错
      `EvolutionConfig.java#evolutionExecutor:86`；恢复后 `diff` 与备份逐字节一致 → 转绿
- [x] `@Async` 方法体不得读 `TenantContext` 的守卫同批落地（含"至少 1 个 `@Async`"反恒真断言）
- [x] 在测试注释中标注该守卫是**静态近似**（不追调用链），定位为 lint 而非证明

## 2. T2 `TenantContext` 跨线程语义

- [x] 新增 `Snapshot`（含 `isEmpty()`）、`capture()`、`restore(Snapshot)`
- [x] `restore(空快照)` 等价于 `clear()`（`remove()` 而非 `set(null)`）
- [x] `GraphRunner.execute()` 改用 `capture`/`restore`，删除手写旧值保存 + 条件还原
- [x] 单测：往返一致 / 空快照清空 / `null` 不抛 NPE / 嵌套逐层还原 / `isEmpty` 语义 / 跨线程 `null` 对照
- [x] 类注释写死规则：跨线程不得依赖 `ThreadLocal` 自动继承，须显式传递
- [x] 未对 `evolutionExecutor` 增加租户自动传播（理由见 `spec.md` §S2.3），决策写入 `docs/03`

## 3. T3 `/actuator` 流量不进 Langfuse —— **部分达成，未达目标**

- [x] `SafeExporter` 按规则识别 actuator span（字符串属性或 span 名含 `/actuator`）
- [x] 丢弃粒度改为**按 traceId 整条丢**（+ 512 条短期记忆兜跨批次）
- [x] 单测：整条丢（含子 span）、业务 span 不受影响、跨批次记忆生效
- [x] 未改动 `management.endpoints` / 采样率 / 指标口径
- [ ] **目标「0 条 actuator 相关 trace」未达成**：残留 1~2 条只含安全子 span 的无名 trace / 3 次抓取
      —— 原因：子 span 先于根 span 结束并导出，根 span 的 traceId 还没被学到（详见 `spec.md` §S3.5）
- [x] 平台反查：**具名** actuator trace = 0，`actuator_traces=0`（`outputs/actuator-trace-filter-*.json`）
- [x] 同一窗口内业务 trace 仍在（39 条：chat / 各端点 / 三个调度任务）→ 排除"导出全挂"造成的假 0
- [x] 采样层两种接法（builder `setSampler` / `Sampler` Bean）均实测无效，代码已撤（保留注释记录失败）
- [x] 对照实验（过滤关闭 = 探针模式）：3 次抓取 → 3 条具名 actuator trace，且**探针直接证明**
      安全子 span 与 actuator 根 span 属同一 trace

## 4. 回归（硬性）

- [x] 编译通过
- [x] 全量单测 **220/220**（基线 211 + 新增 9：守护 2 + 租户 5 + 导出 2）
- [x] 真实 E2E `scripts/e2e_live.py` **22/22**（`outputs/e2e-live-leftovers-143432.json`）
- [x] 三牌判据仍为"协议完整性"（`MAX_ADVICE_TIERS` 未改动，`git diff` 无相关改动）
- [x] 真实 LLM 链路（非 mock），未使用假上游
- [x] 洞察回归 **4/4**（`llm.inflight` 峰值 ≤ 24、经网关）——`outputs/leftovers-insight-140603.json`
- [x] 附带确认 ADR-34 仍有效：平台侧可见 `task evolution.reflect` → `llm.attempt` 正常嵌套

## 5. 本轮新发现（不在原计划内，如实记录）

- [ ] **洞察路径的 trace 碎片问题**（既有，与本轮改动无关）：
      单次 `/insight/analyze` 会产出 **~100 条无名 trace**（构成如
      `embedding text-embedding-v3` + `llm.attempt` + `http post` + `chat glm-4-flash`，无 HTTP 根 span）。
      分辨实验：**不抓 actuator**、只跑洞察回归（`logs/run_insight_only.sh`）→
      `total_traces=100 actuator_traces=0 names={"": 100}`，证明与 T3 过滤无关。
      → 已记入遗留队列，需单独立项。
- [x] `langfuse.trace.name` 不为空的历史结论未受影响

## 6. 未验证 / 已知局限

- [ ] `@Async` 守卫是**静态近似**：只看直接引用，不追方法调用链，也不做数据流分析
- [ ] T3 残留无名 trace（1~2 条/3 次抓取）**未消除**，且未查明 Boot 覆盖自定义采样器的确切顺序
- [ ] `TenantContext` 的 `restore` 语义只由单测 + E2E 覆盖；`GraphRunner` 无专属单测
- [ ] 生产口径（5760 条/天）为外推估计，未在生产环境实测
