# phase29 · checklist（F1+F4+F6）

> 判据见 `tasks.md`（实现前写死）。DoD = 编译 + 单测 + 触达面 E2E + **在 Langfuse 里查到**。

## A. 实现

- [x] A1 三件套（tasks / checklist；spec 并入 ADR-62 §八，不另开文件）
- [x] A2 **F1**：`app.llm.chat-observation`（默认 `false`）→ `ObservationRegistry.NOOP`
- [x] A3 **F4**：`attemptSpan` 加 `llm.endpoint`（值取 `primaryBaseUrl` / `tier.baseUrl()`）
- [x] A4 **F4 根因**：把 `llm.endpoint` 加进 `SafeExporter.EXACT` 白名单
- [x] A5 **新发现**：白名单丢 key 时**一次性 WARN**（把静默变响）
- [x] A6 **F6**：导出器启动自报**补端点**
- [x] A7 yml 里写明 `chat-observation`（可发现性）

## B. 验证（同一次冒烟，Langfuse 实测）

| # | 判据 | 结果 |
|---|---|---|
| J1 | 孤儿模型 trace 消失，且业务 trace 仍含 `llm.attempt`+usage | ✅ 孤儿 **11 → 0**；业务 `chat` 14；usage 含 `cache_read` |
| J2 | `llm.endpoint` 出现在 Langfuse | ✅ **11 条**，值 `https://api.deepseek.com` |
| J3 | 导出器启动自报 | ✅ 应用日志：`[langfuse] 导出已启用：endpoint=…` |
| J4 | 双计修复可量化 | ✅ 同窗口 `业务:孤儿` 由 ≈1:1 → **14:0** |
| J5 | 无回归 | ✅ 单测 **363/363**、E2E **22/22**、无新增 ERROR |
| J6 | 丢弃变响生效 | ✅ 上线即报出 **8 个**被静默丢的 key（`uri`/`http.url`/`method`/…） |

## ⛔ 未验证 / 已知限制

1. **F2/F3/F5 未做**：embedding、JEV/Vision 仍无观测；**无名空壳 trace 仍有 11 条**（F5 要动 ADR-34/35 的传播面）。
2. 孤儿/空壳比例只在**冒烟窗口**量过，**不是生产流量占比**。
3. F1 关掉 Spring AI 观测后，`http post` 子 span 也随之消失 —— **未单独评估**这是否有别的用途（当前看它属性为空且被白名单丢，故无损）。
4. ⛔ **`uri`/`http.url` 被丢是刻意的**（query string 含用户原话）→ **不要**把它当缺陷去"修"。
5. 白名单 WARN 每个 key 只打一次、上限 64 个 —— 足以发现，不足以长期审计；**没有**周期性核对机制。
