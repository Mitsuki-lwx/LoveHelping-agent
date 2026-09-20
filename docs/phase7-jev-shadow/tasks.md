# phase7-jev-shadow · 任务拆分

> **完成情况（2026-09-20）**：T1–T9 全部完成。实测结果见 `spec.md` §S6，逐条自检见 `checklist.md`。
> 结论一句话：**误报侧有真实数据支撑（800 条 0 越阈），但召回侧在真实数据上仍是空白 → 暂不开 `enforce`。**

> 背景：`docs/phase7-guardrail-recall/`（已推送 `f1a37df`）给自伤护栏加了 Jev 第二信号，
> 实测召回 `3/8 → 8/8`、误报 `0`。但那个 `0 误报` 是**我自制的 12 例花园**上数出来的——
> 真实流量的误报率、概率分布、开启后的拦截占比**全部未知**。阈值 0.6 也是在这 12 例上标的。
>
> 所以开启拦截（`enforce`）之前，先补一轮**影子观测**：判、记、不拦。

## 目标

1. 让护栏第二信号具备**三态模式**：`off`（默认，等于接入前）/ `shadow`（判定并落库、**绝不改变响应**）/ `enforce`（判定 + 拦截）。
2. 把每次判定的**概率值**落进 `guardrail_event`（审计表本来就是为"误报率监控"建的），使分布可 SQL 查询。
3. 用**真实消息语料**（DB 里 53,028 条加密 USER 消息）跑一轮影子观测，产出：
   - 概率分布直方图；
   - 「阈值 → 拦截占比」曲线（决定 enforce 是否可开）；
   - 高分区的人工复核，给出真实误报率的第一手估计。

## 边界

**做**：

- `JevProperties.Guardrail` 的 `enabled: boolean` → `mode` 枚举（单一开关，避免 `enabled=false + shadow=true` 这类非法态）。
- Flyway 迁移给 `guardrail_event` 加 `signal_score` / `signal_threshold`（DDL 只经 Flyway，AGENTS.md §4）。
- `JevSelfHarmSignal` 由 `flagged()` 改为 `judge()`，返回概率 + 是否越阈，**判定与决策分离**。
- 抽掉 `GuardrailAdvisor` 与 `ChatEntry` 各自的哈希/落库重复（统一到一个记录器）。
- 真实语料观测脚本 + 可复核的原始证据落 `outputs/`。

**不做**：

- 不改词典规则、不改 L1/L2/L3 梯度语义。
- 不动 `minProbability` 默认值（本轮只**测量**，改值是下一轮的事，且要拿本轮分布做依据）。
- 不默认开启 `shadow`，更不开启 `enforce`（外部依赖 + 未算成本 + 未灰度）。
- 不把解密后的真实消息原文提交进仓库（`outputs/` 已 gitignore；正文只在本地流转，文档只留统计量）。

## 拆分与顺序

| # | 任务 | 产出 |
| --- | --- | --- |
| T1 | 三件套文档 | 本目录三份 md |
| T2 | 模式枚举 + 判定/决策分离 | `JevProperties`、`JevSelfHarmSignal` |
| T3 | 审计补概率字段 | `V24__guardrail_event_signal.sql`、`GuardrailEvent`、记录器 |
| T4 | 接点改造 | `ChatEntry.guardrailCheck`（shadow 不抛，enforce 才抛） |
| T5 | 单测：三态语义 | `JevSelfHarmSignalTest` 扩展 + 新记录器测试 |
| T6 | 真实语料抽取 | 临时 Java 工具用**项目自己的** `EncryptionService` 解密，避免与真实实现漂移 |
| T7 | 影子观测跑批 + 人工复核高分区 | `scripts/jev_shadow_observe.py`、`outputs/*.json` |
| T8 | 真实 E2E：shadow 下用户拿到正常回复且事件落库 | SSE 实测 + SQL 查询 |
| T9 | 全量单测 + E2E 22/22 + 提交推送 | — |

## 风险

- **R1**：真实语料是**单个部署的测试流量**（971 个 user_id，但多为脚本账号 `verify_*` / `aev_*`），
  不代表真实用户分布 → 结论必须标注适用边界。
- **R2**：Jev 单次约 0.3~0.7s，几千条串行不现实 → 抽样 + 小并发；并发过高可能触发厂商限流，
  需记录 429/529 计数，不能把限流失败当成"低风险"。
- **R3**：解密失败（密钥变更/租户不匹配）会让样本有偏 → 统计解密成功率并如实报告。
