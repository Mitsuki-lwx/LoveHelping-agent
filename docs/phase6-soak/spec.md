# Phase 6 收尾 · 长时混合负载稳定性 — 规格（spec）

> 配套：`tasks.md`（范围）、`checklist.md`（AC）
> 环境：本机单实例，MySQL 8 / PostgreSQL 16 + pgvector / Redis；mcp-server 独立进程（8125）；
> 上游 `https://open.bigmodel.cn/api/paas/v4` + dashscope 降级链。参数本轮**冻结**。

## 1. 被测对象与锚点

| 项 | 值 | 来源 |
| --- | --- | --- |
| 代码版本 | `387a929`（`feat(capacity): 容量口径对齐厂商上限 + 超容有界排队`） | git |
| 在途闸门 `app.online.max-inflight` | 24 | `application.yml` |
| 有界排队 `wait-ms` / `queue-capacity` | 3000ms / 24 | 同上（ADR-29） |
| 网关并发 `app.llm.max-concurrent-calls` | 24 | 同上 |
| 图线程池 `app.graph.executor` | core=24 / max=24 / queue=0 | 同上 |
| 厂商并发上限（实测） | ≈ 24（超额立即 429，不排队） | §8.8(1) |
| 每用户突发桶 | 8 令牌 / 1 在途·s（**防脚本**，非容量） | ADR-29 §4 |

**因此 soak 的负载点选在"在途 = 24"**：这是闸门与厂商上限的交汇处，也是最有意义的稳定点——
再往上会把超出的请求推进有界队列，队列深度本身就是被测指标之一。

## 2. 为什么"30 分钟"而不是"压到崩"

短时阶梯压测（`loadtest.py`）测的是**容量边界**，已经做过（§8.8）。
本任务测的是**持续性**：在稳态负载下，系统会不会**缓慢劣化**。三类只随时间显形的问题：

1. **准入额度泄漏**：`OnlineLoadTracker` 用公平信号量发放 permit，`exit()` 里有一道
   `if (availablePermits() < maxInFlight) release()` 的防御。只要有一个路径（异常/取消/断连）
   漏掉 `exit()`，permit 就会**永久少一个** → 可用容量单调下降，最终所有请求都在队列里等超时。
   短跑看不出来（少 1 个 permit 在 24 并发下几乎无感），长跑会显形为**拒绝率随时间上升**。
2. **连接/线程泄漏**：SSE 是长连接，若 `doFinally` 未覆盖某条异常路径，Hikari 连接或图线程不归还 →
   `hikaricp_connections_pending` 出现非零稳态、或 active 缓慢逼近 max。
3. **队列深度单调爬升**：若释放侧慢于获取侧，`online.queue.depth` 的稳态值会随时间抬升。

**判定原则：看"趋势"而不是"单点值"。** 所以采样必须落原始时间序列，判定用前后窗口对比，
不允许只看最后一个数。

## 3. 负载设计（混合，不是单一聊天）

单一端点跑 30 分钟证明不了"混合负载"。混合比按下表（每轮为一个"批次"，批次内并发发起）：

| 权重 | 场景 | 端点 | 说明 |
| --- | --- | --- | --- |
| 60% | 普通 SSE 聊天 | `/Love_app/chat/sse` | 触发 RAG 检索 + LLM 流式生成（主路径） |
| 15% | RAG 显式检索 | `/Love_app/chat/sse/rag` | 工具调用路径 |
| 10% | 三牌建议 | `/Love_app/chat/sse`（道歉类 prompt） | 结构化输出 + 后处理 |
| 10% | Agent 多步 | `/Love_app/chat/LoveManus` | 工具循环（MCP 进程参与） |
| 5% | 护栏负向 | `/Love_app/chat/sse`（PUA 类 prompt） | **零 LLM 规则拦截**，用于对照 |

- **连续 worker，不做批次同步**（关键设计）：批次的写法是"发 N 个请求 → 等全部结束 → 再发下一批"，
  并发上限永远等于 N，**有界队列根本不会被用到**，也就测不出队列漂移。
  改为**每个 worker 完成一个请求后立刻发下一个**，worker 数取 **32**：
  在途会稳定贴住闸门 24，剩余 8 个持续落在有界队列里 → `queue.depth` 长期非零，
  "队列深度是否随时间漂移"才成为可观测的量（对应 `spec §5` S3/S4）。
- **每 worker 独立用户**（独立 token）：避免落到"每用户突发桶"维度——
  那是防脚本机制，不是容量（§8.8(4) 的坑：单用户共用 token 会把全局容量测成每用户限流）。
- **每轮 30 分钟**，逐请求结果（分类/状态码/TTFT/耗时）全部落 JSONL。
- 负载结束后**继续采样 120s**（`--cool-down`），用于验证 permit 回收（S8）。

## 4. 采样设计

每 **15s** 一次，采自 `/api/actuator/prometheus`（Actuator 已 public，`/actuator/**` 在放行名单）：

| 指标 | Prometheus 名 | 关注点 |
| --- | --- | --- |
| 在途数 | `online_inflight_current` | 是否回落、是否超 24 |
| 队列深度 | `online_queue_depth` | **趋势**（核心判定） |
| Hikari 活跃/空闲/等待 | `hikaricp_connections_active` / `_idle` / `_pending` | pending 稳态必须为 0 |
| Hikari 超时累计 | `hikaricp_connections_timeout_total` | 必须为 0 |
| JVM 堆 | `jvm_memory_used_bytes{area="heap"}` | 稳态不单调增长 |
| JVM GC 次数/耗时 | `jvm_gc_pause_seconds_count` | 无 Full GC 风暴 |
| 准入事件计数 | `online_inflight_*`（entered/queued/queue_full/wait_timeout/rejected/interrupted） | 拒绝**分类**归因 |
| LLM 用量 | `llm_tokens_*`（若暴露） | 成本核算 |

同时按批记录（驱动脚本侧）：每请求 `ok / overloaded(4003) / rate_limited(429) / err`、TTFT 中位与 P95、HTTP 5xx 计数。

**注意**：Micrometer 的 `online.inflight.current` 是 Gauge，Prometheus 名形如 `online_inflight_current`
（点号→下划线）；`online.inflight.entered` 是 Counter，形如 `online_inflight_entered_total`。
脚本对两种命名都做兼容匹配，避免因命名假设导致采样为空（**空采样必须让整轮判定失败，不得静默通过**）。

## 5. 验收标准（判定规则，可逐条执行）

采样序列分两段：**预热段** = 前 5 分钟（丢弃，对齐 §8.8 的"预热轮"教训）；
**稳态段** = 第 5–30 分钟。

| # | 指标 | 判定 |
| --- | --- | --- |
| S1 | 5xx | **稳态段 5xx = 0**（任一 5xx 即失败） |
| S2 | 无说明的拒绝 | 拒绝必须为流内 `event:error`，且文案含"当前咨询较多"/"请求过于频繁"/`retryAfterSec`；**裸 400/500 拒绝**视为失败 |
| S3 | 队列深度趋势 | 稳态段 `queue.depth` **后 1/3 均值 ≤ 前 1/3 均值 + 2**（允许噪声，不允许单调爬升） |
| S4 | 队列深度峰值 | 稳态段峰值 ≤ `queue-capacity`(24)，且**不持续贴顶**（贴顶时长占比 < 20%） |
| S5 | Hikari pending | 稳态段 `hikaricp_connections_pending` 的 P95 = 0 |
| S6 | Hikari 超时 | `hikaricp_connections_timeout_total` 增量 = 0 |
| S7 | Hikari active | 稳态段峰值 < `maximum-pool-size`（不逼近上限），且负载停止后 60s 内回落到基线 ±2 |
| S8 | 在途归零 | 负载停止后 **≤ 60s** 内 `online_inflight_current` = 0（**permit 无泄漏**，核心断言） |
| S9 | 准入拒绝率 | 在途 = 24 的档位下，**闸门硬拒率 ≤ 20%**，且绝大部分尖峰应被有界队列吸收 |
| S10 | 厂商 429 | 单独计数并报告；若 > 0，说明闸门对齐被破坏 → 失败 |
| S11 | JVM 堆 | 稳态段每次大 GC 后堆回落（不单调增长）；无 `OutOfMemoryError` |
| S12 | 错误归因 | 稳态段 `err` 类（非拒绝的失败）比例 ≤ 1%；每一条 `err` 必须能在日志里找到原因 |
| S13 | 可复现 | 驱动脚本 + 参数 + 原始 JSONL + 汇总 JSON 全部落 `outputs/`，命令写入 `docs/09` §8.9 |

**失败处理**：任一条不通过 → 定位根因 → 修复（含单测）→ **重跑整轮 30 分钟**，
不允许"只重跑失败的那一段"。

## 5.1 S10 口径修订（2026-09-16，据实改写，非事后放宽）

**触发**：本轮实测 S10 不通过（上游降级 86.66%），但**闸门对齐并未被破坏**
（在途 p50 = max = 24，全程未越界）。定位后发现 **S10 的隐含前提不完整**：

> 原表述"若 429 > 0，说明闸门对齐被破坏"——把厂商限流**只当成一个"对齐就没事"的坎**。

**两轮直连探测（绕过应用）把变量分开**（脚本与原始数据见 `docs/09` §8.9(5b)）：

| 探测 | 变量 | 结果 |
| --- | --- | --- |
| A 严格串行（并发恒为 1） | 只改请求速率 | 30 / 60 / 120 RPM 目标下 **0 拒绝** → **"低 RPM 上限"被排除** |
| B 持续并发（完成即发下一个） | 只改并发 | 16 → **0.9%**、20 → **5.8%**、24 → **3.4%**、28 → **9.2%** 被拒（厂商码全为 `1302`） |

**结论**：厂商的 429 是**并发的函数**，**从并发 ~16 起就非零**，并随并发上升；
**24 这一档本身已带 ~3% 拒绝**，不存在"对齐到 24 就安全"的干净坎。
→ **闸门取 24 = 把工作点放进必然产生 429 的区间，零余量**；持续负载下 429 非零是**结构性必然**。

soak 的 429 比例（~25% 网关调用）比直连 24 并发的 3.4% 高约 8 倍，领先解释
（**待逐条定量证实**）：熔断恢复**惊群**（平稳 24 vs 突发 24 不是同一工况）、
**重试/降级不持有 permit**（`LlmGateway` 先 release 再订阅重试）、
**两处旁路调用**（`SkillReflector` / `InsightService` 直连裸模型，绕过网关准入）。

**修订后的 S10 判据**（对后续轮次生效）：

| # | 指标 | 判定 |
| --- | --- | --- |
| S10a | 闸门对齐 | 稳态段 `online_inflight_current` 峰值 ≤ `max-inflight`（**对齐未被破坏**） |
| S10b | 闸门余量 | 直连同并发档位的拒率必须可忽略（当前 24 档 **3.4% 不可忽略** → 需下调闸门或证明余量充足） |
| S10c | 厂商 429 | 闸门**留出余量** 且 **降级链可用** 时 = 0；否则按上游归因单独报告，**不判应用失败**，但必须记录 |
| S10d | 降级链健康 | 主/备至少一条可用；两条同时不可用 ⇒ 判为**环境阻塞**，整轮结论标注"未验收" |

**本轮结论**：S10a 通过；S10b/S10c 不通过（零余量 + dashscope 不可达）；S10d 不通过。
⇒ 本轮**不判"全部通过"**，S10 待**留出余量 + 环境恢复**后重跑关闭。原 S10 结果**保留在案**，不覆盖。

**先例**：与 `docs/09` §6.1 更正 2026-09-07"厂商无硬并发限流 ≥40"属同类操作——**更正错误前提，而非放宽标准**。

## 6. 与既有验收的关系

| 既有 | 覆盖 | 本任务补什么 |
| --- | --- | --- |
| §8.5 F6/F7/F8 故障注入 | 单点故障路径（超时/429/断连） | 持续负载下的**累积**效应 |
| §8.8 阶梯压测 | 容量边界（8/24/32/48/64） | **稳态持续** 30 分钟不劣化 |
| §7 E2E 冒烟 22 项 | 功能正确性（单次） | 功能正确性在**长时负载后**仍成立 |

## 7. 复现命令

```bash
# 0) 真实栈：mcp-server（必须先起，主应用强依赖 8125）
java -jar mcp-server/target/mcp-server-0.0.1-SNAPSHOT.jar \
     --server.address=127.0.0.1 --server.port=8125

# 1) 主应用（local profile；凭据在 target/classes/application-local.yml，勿 mvn clean）
java -jar target/lwx-ai-agent-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=<PORT>

# 2) 独立复验 E2E（22 项）
python scripts/e2e_live.py --base http://127.0.0.1:<PORT>/api --output outputs/e2e-live-soak.json

# 3) 30 分钟混合负载长压（连续 worker：闸门 24 + 队列 8）
ADMIN_API_KEY=<key> python scripts/soak.py --base http://127.0.0.1:<PORT>/api \
    --minutes 30 --workers 32 --sample-sec 15 --cool-down 120 --output outputs/soak-<ts>
```

## 8. 结果

见 `checklist.md` §D 与 `docs/09` §8.9（本轮实测填写）。
