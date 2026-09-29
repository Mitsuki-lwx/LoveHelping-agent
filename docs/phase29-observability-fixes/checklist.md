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

---

## C. F2 + F3（续做，用户批准）

- [x] C1 **F2**：`embedding.call` span（打在 **`doEmbed()` 咽喉点**，不是 `call()` 入口）
- [x] C2 **F3a**：`jev.call`（`JevClient.answers`，`score`/`noul` 共用）
- [x] C3 **F3b**：`vision.call`（`VisionChatClient.chat`）
- [x] C4 7 个新 attribute 加进 `SafeExporter.EXACT`
- [x] C5 新增视觉探针（`scripts/probe_vision_span.py` + `run_vision_probe.sh`）—— 该路径此前无装置覆盖
- [x] C6 构造器签名变更后同步测试调用点（33 个 error → 0）

| # | 判据 | 结果 |
|---|---|---|
| J7 | 三类 span 在 Langfuse 里可见 | ✅ `embedding.call` 42+ · `jev.call` 33+ · `vision.call` 1（含完整 trace 树） |
| J8 | 标签没被白名单丢 | ✅ `embedding.model/batch/outcome` · `jev.field/outcome` · `vision.images/outcome` 均在 |
| J9 | **不含原文** | ✅ 只记模型名/计数/结局/字段名，无 prompt/图片内容 |
| J10 | 无回归 | ✅ 单测 **363/363**、E2E **22/22** |

### ⭐ 两个坑（都是我在实施中犯的）

1. **span 打在没人走的入口**：第一版把 `embedding.call` 放在 `call(EmbeddingRequest)`，
   而真实检索走 `embed(...)` → 一条都没有。**要打在真正被走的咽喉点**。
2. **第三次量具自伤**：`vision.call` 查不到，真因是**我的探针响应一回来就 `taskkill /F`**，
   `BatchSpanProcessor` 队列里最后一批 span 没 flush → **装置把证据自己杀了**。
   加 6s flush 窗口后立刻出现。
   ⛔ 运维含义：**硬杀进程会丢最后一批 telemetry**（生产靠优雅停机 flush）。

---

## D. F5 复核（**原命题不成立**，改为「可读性」）

准备做 F5（消掉 SSE 无名空壳 trace）时先诊断，**推翻了 ADR-62 §二 的一半**：

| 事实 | 证据 |
|---|---|
| 空壳**不是**业务请求的重复 span | 出现在 login 之后、首条 `chat` 之前（7 条间隔 ~20ms），该时刻**没有**对应业务 trace |
| 空壳**没走到应用** | 那 50ms 应用日志**完全空白**（连 register/login 都有的 `B3PropagatorExtractor` DEBUG 也没有）→ 未过 filter 链 |
| 空壳**属性全空** | `metadata.attributes = {}` —— 不是"没属性"，是**被导出白名单全丢** |

⇒ §二 的"一次请求 2~3 条 trace（双计）"**只对孤儿模型 span 成立**（F1 已修，11 → 0）；
**无名空壳是独立的、早于 pipeline 就结束的短请求，不是双计。**

⛔ **因此 F5 原命题不成立 → 本轮不动 ADR-34/35 的上下文传播面**（高风险改动不该建立在错误前提上）。

**改为修可读性**（小、安全、不动传播）：

- [x] D1 把 `method` / `status` 加进 `SafeExporter.EXACT`（二者内容无关）
- [x] D2 ⛔ `uri` / `http.url` **继续丢**（query string 含用户原话）
- [x] D3 定向复现验证：未认证 SSE（HTTP 400）×3

| 判据 | 前 | 后 |
|---|---|---|
| 空壳在 Langfuse 里的可读性 | 匿名 + `attributes={}` | ✅ `http get /Love_app/chat/sse` + `method=GET` + `status=400` |

### 📌 本轮第三次更正自己

**"看起来像断裂" ≠ "是断裂"**。先把"这些 span 属于哪次请求"证掉（**时间对齐 + 日志对齐**），
再决定动哪里；否则会为一个**不存在的缺陷**去做最危险的那类改动。
