# 路由规则漏判修复 —— 验收清单（checklist）

> 结果记录：2026-09-20。未通过项写明原因。

## 0. 前置
- [x] `tasks.md` / `spec.md` 写完才动代码

## 1. 实现
- [x] `needTools` 不再依赖"关键词在前 6 字内"：强意图短语任意位置命中（`find()` + 三段信号）
- [x] 弱主题词（天气/地图/最新…）**未**放宽成 contains：要求与动作词相邻（≤8 字）或处于句首
- [x] `isAdviceRequest` 补上"求说法/求办法 + 沟通动作"两种形态；并补了 `null` 守卫（原实现会 NPE）
- [x] 未改动 `isOffTopic` / `isSimpleQuestion` 判据

## 2. 测试
- [x] 3 个工具意图用例全部命中（改前全不命中）
- [x] 2 个话术请求用例全部命中（改前全不命中）
- [x] 负例：情感叙述含"天气/地图"**不得**判成工具意图（`他跟我说周末天气好的话就带我去海边` 等 3 例）
- [x] 负例：`有什么办法提高网速` / `有什么办法能让他还钱` **不得**判成话术请求
- [x] 原有 `CapabilityRouterTest` 8 个用例全通过（本轮后 12 个）
- [x] **反向对照实验**：改回旧实现 → **2 个新用例精确失败**（报错文案与预期症状一致：
      `长句里的工具意图必须命中: 北京今天适合户外约会吗？查下天气`、
      `求说法/求办法应命中话术请求: 消息老是已读不回，有什么办法能让他主动找我聊`）；
      其余 10 个仍通过 → 证明新测试确实区分了改造前后；恢复后 `diff` 与备份逐字节一致 → 转绿

## 3. 语料对照（before/after，`outputs/rule-router-*.json`）
- [x] 与人工标签一致率：**20/28 = 71.4% → 25/28 = 89.3%**
- [x] 变化项**恰好**是目标 5 例，无意外改动：
      `agent_weather`→agent、`agent_search_cafe`→agent、`agent_policy`→agent、
      `advice_not_rule_hit`→advice、`advice_invite`→advice
- [x] **无新增错例**（改前 8 个错例 → 改后 3 个，且剩下 3 个改前就有）

## 4. 回归
- [x] 编译通过
- [x] 全量单测 **224/224**（基线 220 + 新增 4）
- [x] 真实 E2E `e2e_live.py` **22/22**（`outputs/e2e-live-routerfix-155934.json`）
- [x] **端到端确认路由真的改变了行为**：应用日志出现 `route=agent` ×3，
      图路径 `[classify, agent_llm, agent_tool, agent_llm, check]` —— 工具节点确实被执行
      （改前这 3 例走 `R_NORMAL`，根本没有工具节点）

## 5. 未验证 / 已知局限（如实记录）

- [ ] ⚠️ **`agent_eval.py` 仍有 3 例 FAIL**（`stale_冷静期新政` 缺 `searchWeb`、`live_约会天气` 无工具、
      `gap_开放关系` 既无工具也无有效回答）。**不是本次改动造成的**：
      ① 日志显示这几次请求期间 **DashScope embedding 报 `SSLHandshakeException: Remote host terminated the handshake`**
      （环境/上游问题，`ParentChildDocumentRetriever` → `PgVectorStore` 路径）；
      ② 模型选的是**内部知识工具**（`KnowledgeSearchTool`）而不是 `searchWeb`/`getWeather`。
      这两条都属下游（工具可用性 + 工具选择），**本轮没修**，需要单独立项。
      另：**改前这 3 例也是 FAIL**（走 `R_NORMAL` 时同样没有工具），故**判定未退化**，但也没有变成 PASS。
- [ ] 既有误判**未处理**：叙述句若在**前 6 字内**出现"天气/地图"仍会误判成工具意图
      （如"那天下雨天气很糟，他都没来接我"）——这是 `HEAD_TOPIC` 保留旧行为的代价，本轮不扩大也不修。
- [ ] `有什么说法/话术` 不区分领域（"面试话术"也会判成话术请求）；`isOffTopic` 只锁工程/学术类，网不住它。
- [ ] 语料只有 28 例、标签由我一人制定；未做更大规模的线上回放。
- [ ] **未接 Jev**（本轮修复零新增外部依赖）；成本核算未做。
