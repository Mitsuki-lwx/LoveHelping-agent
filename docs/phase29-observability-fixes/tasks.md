# phase29 · 观测面修补 F1+F4+F6（ADR-62 §五 的实施）

> 状态：**待实施**。依据：ADR-62 的观测面审计（本地 Langfuse，当下窗口实测）。
> 用户选择：按建议先做 **F1 + F4 + F6**（都小、都不删有用数据、都能用新 trace 立刻验证）。

## 三项改动

| # | 改动 | 做法 |
|---|---|---|
| **F1** | 丢弃**孤儿模型 span**（Spring AI 的 `chat <model>` 自成一条 trace，与业务 trace 1:1 双计） | `LlmProviderConfig` 构造 `OpenAiChatModel` 时按开关传 `ObservationRegistry.NOOP`——我们的 `llm.attempt` 已带 model/usage/outcome/provider，那条**是冗余的** |
| **F4** | 把**实际端点**写进 trace | `LlmGateway.attemptSpan` 加 `llm.endpoint` tag（值取自 `primaryBaseUrl` / `tier.baseUrl()`）——ADR-48 的"生效端点零痕迹"在 trace 上的落点 |
| **F6** | 导出器启用时**启动自报** | `LangfuseTracingConfig` 打一行 `[langfuse] …`，让"导出生效"有可查询证据 |

⛔ **开关式回滚**（本仓约定）：F1 走 `app.llm.chat-observation`（默认 `false` = 不产生冗余 span）。
置 `true` 即恢复 Spring AI 自带观测，无需改代码。

## 判据（实现前写死）

| # | 判据 | 通过线 |
|---|---|---|
| **J1** | F1 生效 | 开冒烟后的**新窗口**里 **不再出现** `chat <model>` 孤儿 trace；同窗口业务 trace 仍含 `llm.attempt`，且**usage 仍在**（没连着有用数据一起丢） |
| **J2** | F4 生效 | `llm.attempt` 的 attributes 里出现 `llm.endpoint`，值 == 启动自报的 primary 端点 |
| **J3** | F6 生效 | 启动日志出现 `[langfuse]` 自报行 |
| **J4** | 双计修复可量化 | 同一窗口内 `#业务 trace` 与 `#孤儿模型 trace` 的比值由 **≈1:1 → 0** |
| **J5** | 无回归 | 单测 363/363（±新增）；E2E 22/22；`llm.fallback`=0；无新增 ERROR |

⛔ **判据纪律**：J1/J4 必须用**新窗口**（历史 trace 跨多个代码时代）；
且要看**同一时间窗**的对比，不能只报"现在没有孤儿了"。

## 刻意不做

- **F2（embedding 观测）/ F3（JEV/Vision 观测）/ F5（SSE 空壳，动 ADR-34/35 的传播面）**——
  留给下一轮；本轮改动越小越好验证。
- 不改 `http post` 的空 attributes（属 Micrometer 自带仪器，动它的收益/风险比不划算）。
