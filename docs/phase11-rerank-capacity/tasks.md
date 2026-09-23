# phase11 · rerank 的容量代价：把「默认开启」的运营参数摆到明面上

> **起因**：ADR-39 决策 4 把 `app.rag.rerank` 默认值改成 `enabled=true / mode=remote`（依据：45 例
> 三轮 MRR **+0.105**，远超噪声带 0.012）。但那份依据来自**串行的离线评测**——
> 它回答了"重排有没有用"，**没回答"它扛不扛得住并发"**。
> 而默认开启意味着：**每一个用户请求**的检索都要多走一次外部调用，且受
> `max-concurrent=2` + 熔断阈值 3 + 15s 熔断窗口（全是**进程内**状态）约束。
>
> 上一轮 `scripts/verify_rerank_load.sh` 的**前提断言**把我拦下了（聊天链路 `Rerank call` = 0），
> 根因是知识候选被记忆挤占 → **ADR-40 已修**。本轮是修复后的首次重跑。

## 目标

1. **量出真实代价**：同负载下 TTFT 分布、拒绝/错误、以及 `max-concurrent=2` 会不会造成**降级**。
2. **回答"降级多少"**：预期并发一上来，`permits.tryAcquire()` 失败 → 抛 `saturated` →
   后处理器捕获 → **静默返回原顺序**（用户无感、重排白开）。要量出比例与许可数的关系。
3. **修掉量具缺口**（不修行为逻辑）：让"降级原因"与"决定降级率的参数"可观测。

## 边界

**做**：
- 前提断言验证（这条 prompt 必须真的走检索 + 重排）。
- 四档并发压测（8/16/24/32，24 是厂商硬上限）。
- 许可数对照：`max-concurrent` = 2 / 8 / 16（同档位、同 prompt、同代码），给出"许可数 → 降级率"曲线。
- 启动回显补 `maxConcurrent` / `failureThreshold`；降级计数补 `reason` 标签 + DEBUG 一行。

**不做**：
- **不改 `max-concurrent` 的默认值**（它是容量参数，改它 = 改三个层的对齐关系 → 需 ADR + 用户拍板）。
- 不碰 embedding/检索链路（ADR-40 已收尾）。
- 不测多实例（`ProviderCircuit` 与信号量都是**进程内**状态，跨实例口径不同 → 另立项）。

## 拆分

| # | 任务 | 产出 |
| --- | --- | --- |
| T1 | 重跑前提断言 + 四档压测（`max-concurrent=2`，代码基线） | `outputs/rerank-load-*.loadtest.txt` |
| T2 | 补量具：回显 `maxConcurrent`/`failureThreshold`；`fallback` 指标加 `reason` + DEBUG | `LocalDocumentReranker` / `RerankDocumentPostProcessor` |
| T3 | 守归因的单测（saturated vs upstream vs missing_key vs other） | `RerankDocumentPostProcessorTest` +3 |
| T4 | 许可数对照 2/8/16 | 三轮压测数据 |
| T5 | 查清"应用层 ERROR"里与重排无关的部分 | 归因记录 |
| T6 | 结论文档 + ADR + 提交 | 本目录 |

## 风险

- **R1**：调大 `max-concurrent` 可能撞上**厂商限流**（重排 API 的并发上限未知，这是未验证项）。
  → 对照里必须区分"本地许可不足"与"上游失败"（这正是 T2 的 `reason` 标签要解决的）。
- **R2**：`32` 档会触发**过载拒绝**（既有容量行为，厂商上限 ≈24），会污染"重排"的读数
  → 解读时必须把拒绝单独算，不混进重排降级率。
- **R3**：每档样本量小（8/16/24/32 次），单档数字有噪声 → 只读**量级与方向**，不下精确定论。
