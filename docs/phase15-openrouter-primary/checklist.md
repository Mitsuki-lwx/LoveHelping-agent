# Phase 15 · 验收清单

> 每条**可独立判定通过与否**。未通过项必须显式写明原因，不允许默认通过。
> DoD = 编译 + 单测 + **真实 E2E**（AGENTS.md §2）
> 勾选时间：2026-09-27。**每条都附实测证据**，无证据的不勾。

## A. 凭据安全（先于一切，违反即停）

- [x] A1 仓库内**无任何**明文 OpenRouter key
  → `git grep -inE 'sk-or-v1-[0-9a-f]{32,}'` **命中 0**。
  唯一的 `sk-or-v1` 字样在 `application-prod.yml:20` 的**注释占位**（`sk-or-v1-...`），
  该文件 `api-key: ${OPENAI_API_KEY:}` 走环境变量。
- [x] A2 `application-local.yml` 未被新增明文
  → `git ls-files --error-unmatch target/classes/application-local.yml` → **未跟踪**（gitignore）。
  ADR-48 已把其中的明文 key 全部换成环境变量引用，改动前备份在 `logs/application-local.yml.bak-adr48-*`。
- [x] A3 探测脚本只从环境变量读 key，不落盘、不回显
  → `grep -lE 'sk-or-v1-[0-9a-f]{32,}' scripts/*.py` **命中 0**；
  `probe_openrouter.py` 走 `os.environ.get("OPENROUTER_API_KEY")`。
  ⚠️ **例外要说清**：`logs/*.sh`（gitignore）里**有**明文 key，因为它们要"一条命令起服务跑 E2E"。
  不入库，但**明文躺在磁盘上** —— 若这台机器有备份/同步，需按需清理。
- [x] A4 提交前 `git status` 全量检查，无 `.env` / 临时 yml 混入
  → 已查：13 个新增项全是源码/脚本/文档，无 `.env`、无临时 yml。

## B. 主链切换

- [x] B1 `spring.ai.openai.base-url` 指向 OpenRouter → `application-prod.yml:19` 与 local 均为
  `https://openrouter.ai/api`；⛔ 已**移除** `completions-path` 覆盖（bigmodel 专用，会让 OpenRouter 拼错路径）
- [x] B2 model = 实测通过的模型
  → 落地为 **`stealth/space-bunny-alpha`**（不是原计划的 `qwen/qwen-plus`）。
  改因见 ADR-48「决策 1 修订」：该账号 `total_credits=0`，付费模型一律 402，
  而它 `pricing.prompt/completion` **均为 0**。实测 40.7~48.7 tok/s、ctx 1M、支持 `tools`。
- [x] B3 API key 只来自 `OPENAI_API_KEY` 环境变量 → 见 A1/A2
- [x] B4 缺 key 时**应用仍能启动** → `BigModelLastResortConfig` 缺 key 返回 `null` 不抛异常，
  实测 E2E 中该 bean 正常注册；主模型缺 key 走 Spring AI 既有行为（调用期报错）
- [x] B5 启动日志能看出实际生效的端点 → **本轮补齐**，见 C9

## C. 三级降级链

- [x] C1 三个 `@Bean` 均注册成功 → 启动日志：
  `[ADR-48] bigmodel 兜底已注册：base=https://open.bigmodel.cn/api/paas/v4 model=glm-4-flash`
- [x] C2 顺序为 openrouter → dashscope → bigmodel → `LlmGateway.degradeTiers()`：
  primary → `fallback` → `last-resort`；启动日志三级各一行，端点与顺序可核
- [x] C3 每个 provider 有**独立**熔断器 → `primaryCircuit/fallbackCircuit/lastResortCircuit` 三个实例；
  单测 `lastResortFailuresDoNotOpenPrimaryCircuit`（兜底级故障不污染主级熔断）
- [x] C4 `LlmGateway` 仍是**唯一**重试所有者 → 每级只调一次 `syncAttempt(..., 1, ...)`，
  降级不经过 `canRetry`；`BigModelLastResortConfig` 里 `RetryTemplate.maxAttempts(1)`
- [x] C5 并发许可覆盖**整条**链 → ADR-31 发现二未退化：许可在 `call()` 入口借、
  `finally` 归还，不在每次 attempt 间借还
- [x] C6 流式：一旦已 emit 就不重放 → 单测 `streamDoesNotReplayAfterEmit`
- [x] C7 退回两级且行为与旧版一致 → `LLM_LAST_RESORT_ENABLED=false`（单测
  `fallbackDisabledSkipsWholeChain`）+ `lastResort=null`（单测
  `nullLastResortDegradesToTwoTiers`）；另有 `fallbackNullButLastResortPresentStillDegrades`
  守住"只看 fallback 非空就放弃降级"这个反向坑
- [x] C8 单测：主挂→备一；备一挂→备二；全挂→BizException(5000) → 三个用例均绿
- [x] C9 主熔断打开时**不**空转重试，直接走下一级 → `CircuitOpenException` 在 `fallbackAllowed` 内
- [x] C10 ⭐ **402 能降级**（ADR-48 补记，最贵的缺口）→ `LlmFailurePolicy.fallbackAllowed`
  白名单补 402；三个回归用例：`paymentRequiredDegradesButDoesNotRetryPrimary` /
  `paymentRequiredFallsThroughToLastResort` / `streamPaymentRequiredDegrades`
  → **不补的后果已实测**：E2E 14/22，三级链一次都没触发
- [x] C11 ⭐ **生效端点可观测**（本轮补的可观测性缺口）→ `LlmGateway` 构造期上报
  启动日志 + `llm_endpoint_configured{level,target}` 指标，实测输出：
  ```
  primary     = https://openrouter.ai/api | stealth/space-bunny-alpha
  fallback    = dashscope-native
  last-resort = glm-4-flash
  attempt=45000ms total=90000ms streamIdle=15000ms lastResortEnabled=true
  ```
  ⛔ 该工具**第一版是错的**（漏 primary + `last-resort` 名字写成 `lastResort` 导致端点串到主端点），
  已修并记入 ADR-48。**假端点比没端点更有害**。

## D. timeout 修正

- [x] D1 `attempt-timeout-ms` = 45000，注释写明依据 → `application.yml:195-204` 附完整推算表
- [x] D2 `total-timeout-ms` = 90000，注释写明"容纳三级" → `application.yml:205-208`
- [x] D3 `stream-idle-timeout-ms` **保持 15000** 且注释说明 → `application.yml:209-210`
- [x] D4 两个值均可环境变量覆盖 → `${APP_LLM_ATTEMPT_TIMEOUT_MS:45000}` /
  `${APP_LLM_TOTAL_TIMEOUT_MS:90000}`
- [x] D5 ⭐ 运行期**实测**生效值（不是只读 yml）→ 启动日志
  `[ADR-48] 网关生效 timeout：attempt=45000ms total=90000ms ...`

## E. 真实 E2E（不可省略）

- [x] E1 服务真启动，端口从启动日志读 → `Tomcat started on port 14700`（脚本 grep，非猜）
- [x] E2 真实 SSE 聊天跑通，拿到完整回答 → 11 个 SSE 场景全部 `success=True`、`errors=[]`
  （含 `rag` 896 字、`advice` 631/704 字、`agent` 896 字）
- [x] E3 实测调用耗时 **< 45s** → 最长 `advice` 24.7s、`rag` 13.5s、`agent` 11.1s
  → 旧 25000 的截断问题**未再出现**。
  ⚠️ `advice` 两个场景有"缺协议可重试一次"的逻辑（`e2e_live.py` 的 `attempts` 循环），
  本次两轮都是**第 1 次即通过**（输出无 `RETRY` 行），所以 24.7s 是**单次**耗时而非两轮之和
- [x] E4 ⭐ **归因：确实打到 OpenRouter** → 三条独立证据（`logs/app-e2e-adr48-011733.log`）：
  ```
  llm_endpoint_configured_total{level="primary",target="https://openrouter.ai/api | stealth/space-bunny-alpha"} 1.0
  llm_call_total{outcome="success",provider="primary"} 9.0
  llm.fallback 指标条数 = 0   ✅ 未发生降级
  ```
  ⛔ **本轮才发现的盲区**：此前日志里 `model=` 的 6 次出现**全是 rerank/embedding/兜底注册**，
  主 LLM 端点零痕迹 → "生效端点是什么"此前**只能靠推断**。已由 C11 补齐。
  ⛔ **判据本身也修过一次**：原判据 `grep -aoiE "last-resort|llm\.fallback" "$LOG"` 每次误报
  "发生降级"，归因后发现 4 次命中**全是启动期日志**。**grep 日志里的级别名 ≠ 降级发生过**，
  已改为只认指标。
- [x] E5 观察到 prompt cache 命中 → `probe_real_prompt_speed.py`：第 2/3 次 `cached=2948/2950`（99.9%），
  `cost=0.000000`
- [x] E6 RAG 链路未回归 → E2E `RAG 工具检索和完整回答` **PASS**（判据含"调用工具"字样 + 内容命中）
- [x] E7 单测全绿 → **311/311**（上轮 308 + 402 三例）
- [x] E8 编译通过 → `BUILD SUCCESS`

## F. 文档

- [x] F1 `docs/03-技术决策记录.md` 新增 ADR-48
- [x] F2 ADR-48 记录并发实测数据（OpenRouter 96 零 429），并与 ADR-42「上游边界按通道测」互相引用
- [x] F3 `docs/02-架构设计.md` 的 LLM 供应商描述与代码一致 → 已更新为三级链 + 标注
  `VisionChatClient` 绕过网关的例外
- [x] F4 `docs/11-环境与装置.md` 补新脚本到 §6 → 补 `probe_answer_stability.py` /
  `probe_primary_endpoint.sh` / `run_answer_stability_ab.sh` / `mb.sh`，
  并写进「换 LLM 端点的标准动作」（含 402 白名单这一步）
- [x] F5 MEMORY.md 更新 → 已压缩重写（原 18.6K 触发注入截断 → 现约 7K）

## G. 显式声明未验证项（**必须逐条写出来，不许含糊**）

- [x] G1 ⭐ **答案质量 A/B 未完成**。已做的是**稳定性 + 拒答阈值**两臂对照
  （`logs/run_answer_stability_ab.sh`），结论见 ADR-48：
  **范围内问题两臂无差**（中位 687 vs 623 字，均 0/4 短答案、三牌齐全）；
  **范围外问题 A 臂更早触发拒答**（中位 160 vs 726 字，3/6 短答案）。
  ⛔ `answer_eval.py` 口径的质量 A/B **仍未跑**。
  ⛔ 样本只有 2 个问题 × 4~6 轮，**远小于 16 例**，报的是去重后文本数。
  ⛔ **未控变量**：两臂检索结果可能不同（B 臂 3/6 显式引用知识库，A 臂 0/6），
  **未做"固定检索、只换模型"的对照** → 净生成速率差异仍未测。
  ⛔ **不得声称质量整体变好或变差。**
- [x] G2 **长时间运行下的 cache 命中率未测**（只测 3~8 次连续调用）
- [x] G3 **闸门 24 是否该上调 —— 本轮不改**，理由已记（涉及"多实例如何折算容量"，
  与 ADR-23"单机不冒充跨实例容量"相关，需另立 ADR）
- [x] G4 ⭐ **`VisionChatClient` 绕过网关未修** —— 手写 RestClient 直连，
  无闸门/熔断/token 计量。已在 `docs/02` 显式标注为已知例外
- [x] G5 **Jev 开启本轮未做**（用户排序：先修 timeout）
- [x] G6 **商汤（SenseNova）无 key，未实测** —— 备选端点之一始终没有数据支撑
- [x] G7 ⭐ **范围外问题的拒答不稳定未修**（A 臂 6 轮 6 种说法、45~442 字）。
  三个处置选项已列在 ADR-48，**本轮未实施**（属提示词改动，需另立 ADR，
  且改完必须用同一套两臂对照复验 —— 本节第一版结论就是这么错的）
- [x] G8 **`llm.endpoint.configured` 只在启动时上报一次**，不是持续探测；
  端点若被运行期改写（当前无此机制）不会立刻反映
