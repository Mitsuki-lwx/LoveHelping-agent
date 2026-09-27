# phase16 · 真实链路 prompt cache 命中实测

> 起因：用户问「现在可以开始做缓存了吗」——ADR-48 把主链切到 OpenRouter
> `stealth/space-bunny-alpha` 时顺带发现该端点有 prompt cache（理想化探测命中 2948/2950），
> 但那个数是**连发同一 prompt** 测的，真实业务链路每次 prompt 都不同。

## 1. 目标

回答一个二选一的问题，并给出可复算的证据：

- **A**：真实链路（前缀 ~4293 字符静态 system + 每轮不同的用户输入 + RAG 检索片段）能不能命中缓存？
- **B**：如果不能，是**应用侧 prompt 结构**的问题（可改），还是**上游行为**（不可改）？

## 2. 不做什么（边界）

- ⛔ 不改 prompt 结构、不动 advisor 顺序 —— 在量清楚之前改 prompt 属于盲改
- ⛔ 不做应用层业务缓存（Redis 落地、结果缓存）—— 与本 phase 无关，另议
- ⛔ 不动 `LlmGateway` 的重试/降级语义（ADR-23 不变式）
- ⛔ 不重新评估 embedding/rerank 的缓存

## 3. 关键前提

- 必须先确认服务真的跑在 `stealth/space-bunny-alpha` 上（local yml 字面值会压过环境变量），
  否则量的是别的端点 → 用 ADR-48 新增的 `llm_endpoint_configured` 指标自证。
- 真实链路的 usage **不走 SSE 事件**（实测 SSE 只有 `message`/`advice`/`error` 三种），
  所以只能从 prometheus 指标侧取数。

## 4. 任务拆分

| # | 任务 | 产出 |
|---|---|---|
| T1 | 网关补 `cached_tokens` 埋点 | `LlmGateway.usage()` + `cachedTokens()` |
| T2 | 写真实链路探针（内容不同的提问 + 指标差值法） | `scripts/probe_real_chain_cache.py` |
| T3 | 写一键装置（起服务+探针+收尾串同一条命令） | `logs/probe_real_chain_cache.sh` |
| T4 | 跑首轮，观察到 141/1180 二元分叉 | 记录现象 |
| T5 | **逐个证伪候选解释**（每条都要有对照实验） | 3 个被推翻的解释 |
| T6 | 加 payload 取证能力，定位分叉点 | `PromptPayloadDump` |
| T7 | 复跑取稳态结论 | 命中率分布 |
| T8 | 发现并修一个附带缺陷（total-timeout 无日志） | `LlmGateway` warn + 指标 |
| T9 | 写 ADR + 更新记忆 | `docs/03`、`.workbuddy/memory/` |

## 5. 验收标准

见 `checklist.md`。核心三条：

- [ ] 真实链路命中率必须有**逐轮**数据，不是聚合值
- [ ] 任何「结构性缺陷」的结论必须有**对照实验**支撑，推断不算
- [ ] 结论必须区分「应用侧可改」与「上游不可改」

## 6. 意外发现（不在原计划内）

- 真实链路存在**确定性超时**：「准备结婚/两家对婚礼花费有分歧」这题 3 次运行 **100% 撞 90s
  total-timeout**，且超时**零日志** → 促成 T8。
- 上游 cache **按块命中**：`cached` 值恒落在 1180 / 1861 / 2321 / 2452 / 2501 等离散点，
  1180 反复出现 → 疑似固定块大小，非随机抖动。
