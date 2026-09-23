# phase11 · 实测结果与结论

> 状态：已交付（2026-09-23）。**产品行为逻辑零改动**（只加了观测）；`max-concurrent` **默认值未改**（属容量参数，需拍板）。

## S1 装置

`scripts/verify_rerank_load.sh`（上一轮写好、被前提断言正确拦下；ADR-40 修复检索后首次跑通）：

- 每轮**真启动**（走 `application.yml` 默认值，不设任何 `RERANK_*` 覆盖）+ 四档真实 SSE 压测（8/16/24/32，24 是厂商硬上限）。
- 压测前**关掉本地 8091** → 成功的重排只可能走远端（排除"其实走的是本地"）。
- 每轮记录：TTFT 分布、拒绝/错误、`rag_rerank_*` 指标、`saturated/circuit open/unavailable` 痕迹、应用层 ERROR。

**前提断言通过**（这是上一轮拦住我的那一步）：`RAG_RETRIEVAL=1`、`Rerank call=1`、`Rerank ok=1`。

## S2 核心发现：`max-concurrent=2` 让 79% 的重排**静默降级**

三轮对照，**同脚本、同档位、同 prompt、同代码**，只有 `APP_RAG_RERANK_MAXCONCURRENT` 不同：

| `max-concurrent` | `executions` | `fallback(saturated)` | `fallback(upstream)` | 成功重排 | **降级率** |
| --- | --- | --- | --- | --- | --- |
| **2**（当前默认） | 58 | **46** | 0 | 12 | **79.3%** |
| 8 | 58 | 16 | 1 | 41 | 27.6% |
| **16** | 58 | **0** | 0 | 58 | **0%** |

- `executions=58` 与"成功聊天请求数 58"**完全吻合** → 每次检索都执行了重排（不是没走这条路）。
- 降级是**静默的**：用户拿到的是完整正常回复，没有错误、没有 5xx；只有指标能看出来。
- 原因分布由本轮新加的 `reason` 标签直接读出（此前只有一个总计数）。

> 📌 **这不是"重排坏了"，是"重排大部分时候没参与"。** `tryAcquire()` 在并发 ≥3 时就会失败
> （一次重排占 0.4~1.3s，2 个许可 → 约 5 次/秒的吞吐上限），超出部分立刻抛 `saturated`
> 并降级为原顺序。**用户无感，指标里只有一行计数。**

## S3 与 ADR-39 的冲突（本轮最重要的结论）

ADR-39 决策 4 依据"45 例三轮 MRR **+0.105**"把 `rerank` 默认打开。那份依据来自**串行的离线评测**：
它回答"重排有没有用"，**没回答"它扛不扛得住并发"**。本轮补上后半：

> **默认 `enabled=true` + 默认 `max-concurrent=2` 是自相矛盾的一对默认值**：
> 开了，但在并发 ≥3 的真实流量下约 **八成请求拿不到这个收益**。

实测的 TTFT 也印证方向（剔除预热档，p50 均值 8/16/24 三档）：

| `max-concurrent` | 8 并发 | 16 并发 | 24 并发 | 均值 |
| --- | --- | --- | --- | --- |
| 2（79% 降级） | 2.54s | 2.92s | 3.67s | **3.04s** |
| 8（28% 降级） | 2.87s | 3.90s | 3.31s | **3.36s** |
| 16（0% 降级） | 3.74s | 3.65s | 3.58s | **3.66s** |

→ 少降级 ≈ 多花约 **+0.6s**（与"重排单次 0.4~1.3s"同量级）。**样本小（每档 8/16/24 次），只读方向。**

## S4 延迟与拒绝（既有行为的再确认）

- **拒绝**：8/16/24 三档两轮均 **0 拒绝 0 错误**；32 档 **过载拒 26~27 / 成功 5（≈81~84%）**
  —— 与容量事实（厂商上限 ≈24）一致，**是既有容量行为，与重排无关**。
- **p95 在 24 档稳定偏高**：三轮分别 **10.54 / 10.30 / 10.97s**（8/16 档都在 3~6s）。
  三轮重现 → 不是偶然；**归因未做**（闸门排队 / 上游 / 重排三者未拆解），已记待办。
- **应用层 ERROR**：三轮 13 / 3 / 3 条。逐条查过构成：
  - 每轮固定 3 条 `MessageAggregator: Aggregation Error`（LLM 流的既有噪声）；
  - MC=2 那轮多出的 10 条，**全是 Tomcat 在客户端断开后写 SSE 的 NPE**
    （`MimeHeaders` 命中 19 次，栈里没有重排）→ 压测客户端超时/被拒后关连接的既有噪声；
  - **无一条来自重排路径**。

## S5 冷启动：本轮**未复现** 5.21s

`Rerank ok` 首条 **922ms**（上一轮记录的是 5.21s，且 >`timeout-ms=5000`）。
→ 5.21s 那条**只观测到过一次、本轮未复现**，不能当作稳定特征；但也不能因此删除
（两次的差别可能在厂商侧预热状态）。**结论收窄为"未复现"，不做"没问题"的判断。**

## S6 本轮唯一的产品代码改动：补量具（不改行为）

1. **启动回显补 `maxConcurrent` / `failureThreshold`**（`LocalDocumentReranker`）——
   这两个正是**决定降级率**的参数，此前没回显，导致上一轮"45 次降级"只能靠翻源码推断。
2. **`rag.rerank.fallback` 指标补 `reason` 标签**（`RerankDocumentPostProcessor`）：
   取值 `saturated` / `circuit_open` / `missing_key` / `upstream` / `other`（**有限取值，不膨胀基数**）。
   S2 那张表就是它直接读出来的。`reason=other` 非零即说明代码里**新增了降级路径**需要补归类。
3. **降级路径补 DEBUG 一行**（INFO 下不输出，避免高 QPS 刷屏），并**打印完整异常 + cause**
   （只打 `getMessage()` 会丢 cause：实测 `reason=upstream` 时外层消息恒为 "Local reranker unavailable"，
   看不出是限流、连不上还是超时）。

**单测 +3**（`RerankDocumentPostProcessorTest`）守归因：
`saturated` 与 `upstream` 必须可分（前者调 `maxConcurrent`、后者查上游/熔断，**处置动作完全不同**）；
`missing_key` 不得被误归成上游；未知抛出点落 `other`。

**对照实验**：把 `reasonOf` 改成恒返回 `"other"` → `fallbackReason_distinguishesLocalCapacityFromUpstream`
与 `fallbackReason_missingKeyIsItsOwnBucket` **精确失败且只它两个**（`unknownGoesToOther` 仍通过，
因为改坏后的行为恰好与它的期望一致）；恢复后与备份逐字节一致。

## S7 建议（容量参数，需拍板）

**把 `app.rag.rerank.max-concurrent` 从 2 提到 16**，依据：

- 16 档实测 **0% 降级、0 上游失败**，58 次重排全部成功；
- 8 档仍有 28% 降级 → **8 不够**；
- 16 与整机闸门（24）及厂商 LLM 并发上限同量级，不越级。

⚠️ **代价与风险**：

- **TTFT p50 约 +0.6s**（方向性；样本小）。
- **厂商 rerank API 的并发上限未知**（本轮 16 档未观测到限流，但 58 次样本推不出"不会限流"）。
  若真限流，形态是 `reason=upstream` 上升 —— 现在**指标里能直接看到**，这是本轮补量具的直接回报。
- 回滚：`RERANK_MAXCONCURRENT=2` 或 `RERANK_ENABLED=false`（都是配置项，不发版）。

## S8 未验证 / 局限（报忧）

1. **样本量小**：每档 8/16/24 次，单档数字噪声明显 → 只读**量级与方向**，不读精确值。
2. **多实例未测**：`Semaphore`、`ProviderCircuit`、`maxConcurrent` 全是**进程内**状态；
   多实例下"每实例 16 个许可"的全局含义不同（ADR-23 明确"单机不冒充跨实例容量"）。
3. **厂商 rerank 并发上限未探**：只确认 16 的短时突发未失败。
4. **24 档 p95 ≈ 10.5s 未归因**（三轮重现，但没拆解是闸门排队、上游还是重排）。
5. **criterion**：本轮**只测 rerank**，未在同一装置上跑"`RERANK_ENABLED=false` 的对照轮"
   → TTFT 的绝对差异里重排占多少，**没有干净分解**（S3 的方向性结论靠"降级率与延迟同向变化"支撑）。
6. 冷启动 **5.21s 未复现，也未证伪**。

---

## S9 实施：默认值落地与复验（2026-09-23，用户拍板后）

### S9.1 改了什么

| 项 | 原值 | 新值 | 依据 |
| --- | --- | --- | --- |
| `app.rag.rerank.max-concurrent` | 2 | **16** | §S2 的许可数对照：2 → 79.3% 降级、8 → 27.6%、**16 → 0%** |
| `app.rag.rerank.connect-timeout-ms` | 500 | **2000** | §S9.3（本轮新发现） |
| `RerankProperties.maxConcurrent` 字段默认值 | 2 | **16** | 与 yml 一致——两处不一致的话，将来某次误删 yml 覆盖会**悄悄退回 79% 降级** |
| 两处超时/并发项 | 硬编码 | 加了环境变量占位符 | `RERANK_MAX_CONCURRENT` / `RERANK_CONNECT_TIMEOUT_MS`，可**运行态回滚** |
| 启动回显 | 无 `connectTimeoutMs` | 补上 | 量具缺口：它与 `maxConcurrent` 同属"决定降级率"的参数 |

### S9.2 复验：默认值全生效、降级归零

真启动，**不设任何 `RERANK_*` 环境变量**（走 yml 默认）：

```
Rerank configured: enabled=true mode=remote endpoint=https://api.siliconflow.cn/v1/rerank
  model=Qwen/Qwen3-Reranker-8B topN=20 topK=5 timeoutMs=5000 connectTimeoutMs=2000
  maxConcurrent=16 failureThreshold=3
executions_total{mode="remote"} = 61
（无任何 rag_rerank_fallback_* 时间序列 → 降级 0 次）
```

| 并发 | 8 | 16 | 24 | 32 |
| --- | --- | --- | --- | --- |
| TTFT p50 | 3.80s | 4.58s | 7.61s | 3.01s（72% 被拒，样本 8 条） |
| 拒绝 | 0 | 0 | 0 | 过载 23 |

单测 **285/285**、真实 E2E **22/22**。

### S9.3 新发现：`connect-timeout-ms=500` 与本机网络不匹配

**怎么发现的**：把 `max-concurrent` 提到 16 后跑第一轮，出现 `fallback` 且原因是
`circuit_open`(2) + `upstream`(6)。因为上一轮**刚补了"打印完整异常与 cause"**，日志直接给出根因：

```
java.lang.IllegalStateException: Local reranker unavailable
Caused by: java.net.http.HttpConnectTimeoutException: HTTP connect timed out   (×6)
Caused by: java.net.ConnectException: HTTP connect timed out                   (×3)
Caused by: java.net.http.HttpTimeoutException: request timed out               (×3)
```

→ **全是连接/请求超时，不是 429 限流**（日志里那 88 处 "429" 是时间戳 `12:57:00.429` 的巧合，不是限流码）。

实测本机到上游的「连接 + TLS 握手」耗时：

| 目标 | 5 次实测（ms） |
| --- | --- |
| `api.siliconflow.cn` | 1112 / 437 / 261 / 815 / 148 |
| `dashscope.aliyuncs.com` | 2797 / 600 / 1009 / 343 / 355 |

**常超过 500ms** → 500 的连接超时在该网络下必然成片失败，并连带把熔断器打开（`circuit_open`）。

**旁证**：同一份 `application.yml` 里 LLM 通道的 `connect-timeout-ms` 是 **3000**，只有 rerank 是 500
—— 500 在本项目里本就是个异常值，不是"刻意的快速失败"。

对照（同脚本/同档位/同 prompt，只改 connect 超时）：

| 轮 | `connect-timeout-ms` | executions | `fallback(upstream)` | `fallback(circuit_open)` |
| --- | --- | --- | --- | --- |
| A2 | **500** | 58 | **2** | 0 |
| A3 | 2000 | 61 | **0** | 0 |
| 复验 | 2000 | 61 | **0** | 0 |

### S9.4 一段被环境干扰的测量（**如实记录，不当作本次改动的证据**）

12:56~13:06 之间跑的三轮，TTFT 比正常高**一个数量级**（8 并发 8.4~23.5s、24 并发 15.7~25.6s）。
**但关掉重排的对照轮（`enabled=false`）同样高**（9.5 / 16.3 / 36.0s）——
→ 说明是**上游/网络此刻整体偏慢**，与重排、与本次改动无关。
13:11 复验时已恢复（8/16/24 = 3.80 / 4.58 / 7.61s）。

> 这一段的价值在于**它没有被误读成"开重排导致 TTFT 25 秒"**——判据是：
> **把被测组件关掉再跑一遍**。若关掉后同样慢，就不是它的问题。

### S9.5 重排对 TTFT 的净代价（补上上一轮"未验证"的一条）

同期配对（13:11 开启 vs 13:15 关闭，网络状态正常）：

| 并发 | 重排关 | 重排开 | Δ |
| --- | --- | --- | --- |
| 8 | 2.65s | 3.80s | **+1.15s** |
| 16 | 3.15s | 4.58s | **+1.43s** |
| 24 | 5.53s | 7.61s | **+2.08s** |
| 均值（8/16/24） | **3.78s** | **5.33s** | **+1.55s** |

**读作"约 +1~1.5s（p50）"**，不读精确值：单档样本仅 8/16/24 次，
且 24 档的 p95 出现反向（关 12.81s vs 开 8.87s）→ 噪声不小。
比"重排单次 0.4~1.3s"高是合理的：还有等待许可与排队的时间。

### S9.6 仍未验证

- 厂商 rerank API 的**并发上限**（16 档两轮未观测到限流，推不出"不会限流"）；
- **多实例**：许可/熔断/超时全是进程内状态；
- 12:56~13:06 那段网络为何变慢（只观测到现象与"关掉重排也一样"，未追到上游或链路层）；
- 24 档 p95 ≈10.5s（既有，三轮重现）仍未归因。
