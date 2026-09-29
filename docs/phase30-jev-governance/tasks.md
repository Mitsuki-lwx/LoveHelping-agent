# phase30 · JEV 治理缺口（ADR-62 待办 #1）

> 状态：**待实施**。源于 ADR-62 §四/§九：F3 只给 JEV 补了**观测**，
> 它仍是**唯一绕过 `LlmGateway` 的外部依赖** —— 无并发闸门、无熔断、无计量。

## 问题（为什么值得做）

| 缺口 | 后果 |
|---|---|
| **无熔断** | JEV 故障时**每一次用户消息**都白等到 `timeoutMs`（当前 8s）→ 用户直接感知<br>（ADR-54 已知：`judge()` 在 `ChatEntry` 的**用户发消息主路径上、同步、先于 SSE 建流**） |
| **无并发上限** | 突发请求 → 无限并发打向外部依赖（连接/配额无界） |
| **无计量** | 没有"调了多少次 / 失败多少"的指标，只能读日志 |

⛔ **不是在问"要不要让它走网关"**：`LlmGateway` 是 `ChatModel` 形状，JEV 既不能生成文本也不能做
embedding，塞进去是削足适履。正确做法是**照抄仓里已有的"外部依赖治理"范式**：
`LocalDocumentReranker` 用 `Semaphore(maxConcurrent)` + `ProviderCircuit(failureThreshold, circuitOpenMs)`
（同一个 `ProviderCircuit` 也被 `LlmGateway` 用）—— **不新造第二套约定**。

## 判据（实现前写死）

| # | 判据 | 通过线 |
|---|---|---|
| **J1** | **熔断 fail-fast** | 连续 `failureThreshold` 次失败后，后续调用**不发 HTTP**（用 stub 的调用计数证明：打开后再调，计数**不增**）且立即返回 `Optional.empty()` |
| **J2** | **并发受限** | `maxConcurrent` 生效（并发许可，不无限 fan-out） |
| **J3** | **语义零变化** | off / 无 key → **0 请求**；任何失败 → `Optional.empty()`（触发既有 LLM 回退）；成功路径逐字不变 → 既有 **6 + 9 = 15** 个 JEV 测试全绿 |
| **J4** | **可观测** | `jev.call` span 带熔断状态标签（新标签须同时加进 `SafeExporter.EXACT`，否则被静默丢） |
| **J5** | 无回归 | 单测 **363**（±新增）、E2E **22/22** |

⛔ 判据纪律：J1 必须**用调用计数证明"没发请求"**，不能只看"返回了 empty"
（返回 empty 也可能只是又超时了一次 —— 那正是要修的东西）。

## 刻意不做

- **不重试**：JEV 失败即回退 LLM（既有语义），加熔断只是为了**别再白等**。
- 不改 `timeoutMs`（8000 是 ADR-54 按真实延迟分布定的，有依据）。
- 不把它接进 `LlmGateway`。
